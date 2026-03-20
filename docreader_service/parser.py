import asyncio
import os
from typing import List, Tuple
from urllib.parse import urlparse

import httpx

from models import ChunkPayload


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


def _extract_text(source_bytes: bytes, ext: str) -> str:
    if ext == ".pdf":
        try:
            return _extract_pdf_text_pymupdf(source_bytes)
        except Exception:
            try:
                return _extract_pdf_text_pdfplumber(source_bytes)
            except Exception as exc:
                raise ParseError("corrupted_file", f"pdf parse failed: {exc}")

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


async def parse_to_chunks(job_id: str, file_url: str, options: dict) -> List[ChunkPayload]:
    chunk_size = int(options.get("chunk_size", 1000)) if options else 1000
    chunk_overlap = int(options.get("chunk_overlap", 150)) if options else 150

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
                metadata={"source_ext": ext or "unknown"},
            )
        )
    return chunks
