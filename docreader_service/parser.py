import asyncio
import io
import os
from typing import List, Tuple
from urllib.parse import urlparse

import httpx
from minio import Minio

from config import settings
from markitdown_parser import convert_docx_to_markdown
from models import ChunkPayload, ImageInfo


class ParseError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _read_source_bytes(file_url: str) -> Tuple[bytes, str]:
    if not file_url:
        raise ParseError("schema_invalid", "file_url is required")

    parsed = urlparse(file_url)
    ext = os.path.splitext(parsed.path or "")[1].lower()

    if parsed.scheme in ("http", "https"):
        try:
            with httpx.Client(timeout=20.0) as client:
                resp = client.get(file_url)
                resp.raise_for_status()
                return resp.content, ext
        except Exception as exc:
            raise ParseError("service_unavailable", f"download failed: {exc}")

    if parsed.scheme == "file":
        local_path = parsed.path
    else:
        local_path = file_url

    if not os.path.exists(local_path):
        raise ParseError("unsupported_file", f"file not found: {local_path}")

    try:
        with open(local_path, "rb") as f:
            return f.read(), ext
    except Exception as exc:
        raise ParseError("corrupted_file", f"read file failed: {exc}")


def _extract_pdf_text_pymupdf(pdf_bytes: bytes) -> str:
    import fitz  # type: ignore

    text_parts: List[str] = []
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page in doc:
            text_parts.append(page.get_text("text") or "")
    return "\n".join(text_parts).strip()


def _extract_pdf_text_pdfplumber(pdf_bytes: bytes) -> str:
    import io
    import pdfplumber  # type: ignore

    text_parts: List[str] = []
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        for page in pdf.pages:
            text_parts.append(page.extract_text() or "")
    return "\n".join(text_parts).strip()


def _extract_docx_markdown(docx_bytes: bytes) -> str:
    try:
        return convert_docx_to_markdown(docx_bytes)
    except Exception as exc:
        raise ParseError("corrupted_file", f"docx to markdown failed: {exc}")


def _extract_text(source_bytes: bytes, ext: str) -> str:
    if ext == ".pdf":
        try:
            return _extract_pdf_text_pymupdf(source_bytes)
        except Exception:
            try:
                return _extract_pdf_text_pdfplumber(source_bytes)
            except Exception as exc:
                raise ParseError("corrupted_file", f"pdf parse failed: {exc}")

    if ext == ".docx":
        return _extract_docx_markdown(source_bytes)

    if ext in (".txt", ".md", ".csv", ".json", ".html", ".htm", ""):
        try:
            return source_bytes.decode("utf-8", errors="ignore")
        except Exception as exc:
            raise ParseError("corrupted_file", f"text decode failed: {exc}")

    raise ParseError("unsupported_file", f"unsupported extension: {ext}")


def _split_text(text: str, chunk_size: int, chunk_overlap: int) -> List[Tuple[int, int, str]]:
    if not text:
        return []
    size = max(50, chunk_size)
    overlap = max(0, min(chunk_overlap, size - 1))

    out: List[Tuple[int, int, str]] = []
    start = 0
    text_len = len(text)
    while start < text_len:
        end = min(text_len, start + size)
        content = text[start:end].strip()
        if content:
            out.append((start, end, content))
        if end >= text_len:
            break
        start = max(0, end - overlap)
    return out


def _safe_token(value: str, fallback: str) -> str:
    raw = (value or "").strip()
    if not raw:
        return fallback
    cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in raw)
    return cleaned or fallback


def _content_type_from_ext(ext: str) -> str:
    ext_map = {
        "png": "image/png",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "webp": "image/webp",
        "bmp": "image/bmp",
        "gif": "image/gif",
        "tiff": "image/tiff",
    }
    return ext_map.get((ext or "").lower(), "application/octet-stream")


def _extract_pdf_images(pdf_bytes: bytes) -> List[Tuple[int, int, bytes, str]]:
    import fitz  # type: ignore

    out: List[Tuple[int, int, bytes, str]] = []
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page_idx, page in enumerate(doc):
            images = page.get_images(full=True)
            for image_idx, img in enumerate(images):
                xref = img[0]
                extracted = doc.extract_image(xref)
                payload = extracted.get("image")
                if not payload:
                    continue
                ext = str(extracted.get("ext") or "png").lower()
                out.append((page_idx + 1, image_idx + 1, payload, ext))
    return out


def _upload_pdf_images(job_id: str, knowledge_id: str, user_id: str, pdf_bytes: bytes) -> List[ImageInfo]:
    try:
        client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )
        bucket = settings.minio_bucket
        if not client.bucket_exists(bucket):
            client.make_bucket(bucket)
    except Exception as exc:
        raise ParseError("service_unavailable", f"minio init failed: {exc}")

    extracted = _extract_pdf_images(pdf_bytes)
    out: List[ImageInfo] = []
    for page_no, image_no, payload, ext in extracted:
        key = f"{user_id}/{knowledge_id}/parsed-images/{job_id}/page-{page_no}/img-{image_no}.{ext}"
        try:
            client.put_object(
                bucket_name=bucket,
                object_name=key,
                data=io.BytesIO(payload),
                length=len(payload),
                content_type=_content_type_from_ext(ext),
            )
            out.append(
                ImageInfo(
                    storage_bucket=bucket,
                    storage_key=key,
                    start_pos=None,
                    end_pos=None,
                    caption=None,
                    ocr_text=None,
                )
            )
        except Exception as exc:
            raise ParseError("service_unavailable", f"minio upload failed: {exc}")
    return out


async def parse_to_chunks(job_id: str, file_url: str, options: dict) -> List[ChunkPayload]:
    chunk_size = int(options.get("chunk_size", 1000)) if options else 1000
    chunk_overlap = int(options.get("chunk_overlap", 150)) if options else 150
    safe_options = options or {}
    user_id = _safe_token(str(safe_options.get("user_id") or safe_options.get("userId") or "anonymous"), "anonymous")
    knowledge_id = _safe_token(str(safe_options.get("knowledge_id") or safe_options.get("knowledgeId") or "unknown"), "unknown")

    source_bytes, ext = await asyncio.to_thread(_read_source_bytes, file_url)
    text = await asyncio.to_thread(_extract_text, source_bytes, ext)
    if not text.strip():
        raise ParseError("corrupted_file", "empty extracted text")

    splits = await asyncio.to_thread(_split_text, text, chunk_size, chunk_overlap)
    chunks: List[ChunkPayload] = []
    for idx, (start, end, content) in enumerate(splits):
        chunks.append(
            ChunkPayload(
                chunk_id=f"{job_id}:{idx}",
                type="text",
                seq=idx,
                start=start,
                end=end,
                content=content,
                image_info=[],
                metadata={"source_ext": ext or "unknown", "user_id": user_id},
            )
        )
    if ext == ".pdf":
        image_infos = await asyncio.to_thread(_upload_pdf_images, job_id, knowledge_id, user_id, source_bytes)
        if image_infos:
            if chunks:
                chunks[0].image_info.extend(image_infos)
            else:
                chunks.append(
                    ChunkPayload(
                        chunk_id=f"{job_id}:0",
                        type="text",
                        seq=0,
                        start=None,
                        end=None,
                        content="",
                        image_info=image_infos,
                        metadata={"source_ext": "pdf", "user_id": user_id},
                    )
                )
    return chunks
