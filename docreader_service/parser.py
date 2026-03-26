import asyncio
import base64
import io
import os
import re
import uuid
from typing import Dict, List, Tuple
from urllib.parse import urlparse

import httpx

from config import settings
from markitdown_parser import convert_docx_to_markdown, convert_file_to_markdown
from models import ImageRef, ReadResponse


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


def _extract_pdf_page_texts_pymupdf(pdf_bytes: bytes) -> List[str]:
    import fitz  # type: ignore

    pages: List[str] = []
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page in doc:
            pages.append((page.get_text("text") or "").strip())
    return pages


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


def _local_name(tag: str) -> str:
    if not tag:
        return ""
    if "}" in tag:
        return tag.rsplit("}", 1)[-1]
    return tag


def _guess_image_ext(content_type: str, part_name: str) -> str:
    ctype = (content_type or "").lower()
    name = (part_name or "").lower()
    if "png" in ctype or name.endswith(".png"):
        return "png"
    if "jpeg" in ctype or "jpg" in ctype or name.endswith(".jpeg") or name.endswith(".jpg"):
        return "jpg"
    if "webp" in ctype or name.endswith(".webp"):
        return "webp"
    if "gif" in ctype or name.endswith(".gif"):
        return "gif"
    if "bmp" in ctype or name.endswith(".bmp"):
        return "bmp"
    if "tiff" in ctype or "tif" in ctype or name.endswith(".tiff") or name.endswith(".tif"):
        return "tiff"
    return "png"


def _build_markdown_with_image_mapping(
    content_sequence: List[Tuple[str, str]],
    image_replacement_mapping: dict,
) -> str:
    if not content_sequence:
        return ""

    markdown_parts: List[str] = []
    for item_type, value in content_sequence:
        if item_type == "text":
            text = (value or "").strip()
            if text:
                markdown_parts.append(text)
            continue
        if item_type == "image":
            marker = (value or "").strip()
            if marker:
                markdown_parts.append(f"![]({marker})")

    markdown = "\n\n".join(markdown_parts).strip()
    if not markdown or not image_replacement_mapping:
        return markdown

    for marker, image_ref in image_replacement_mapping.items():
        markdown = markdown.replace(f"({marker})", f"({image_ref})")
    return markdown


def _extract_docx_markdown_and_images(docx_bytes: bytes) -> Tuple[str, List[Tuple[str, bytes, str]]]:
    try:
        from docx import Document  # type: ignore
    except Exception as exc:
        try:
            return _extract_docx_markdown(docx_bytes), []
        except ParseError:
            raise
        except Exception:
            raise ParseError("corrupted_file", f"python-docx import failed: {exc}")

    try:
        doc = Document(io.BytesIO(docx_bytes))
    except Exception as exc:
        # Keep compatibility with previous behavior on malformed docx.
        return _extract_docx_markdown(docx_bytes), []

    rel_embed_key = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}embed"
    rel_link_key = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}link"

    content_sequence: List[Tuple[str, str]] = []
    image_replacement_mapping: dict = {}
    extracted_images: List[Tuple[str, bytes, str]] = []
    image_idx = 0

    body = doc.element.body
    for paragraph in body.iter():
        if _local_name(getattr(paragraph, "tag", "")) != "p":
            continue

        paragraph_text_parts: List[str] = []
        for node in paragraph.iter():
            node_name = _local_name(getattr(node, "tag", ""))
            if node_name == "t":
                if node.text:
                    paragraph_text_parts.append(node.text)
                continue
            if node_name in ("tab",):
                paragraph_text_parts.append("\t")
                continue
            if node_name in ("br", "cr"):
                paragraph_text_parts.append("\n")
                continue
            if node_name != "blip":
                continue

            rel_id = node.get(rel_embed_key) or node.get(rel_link_key)
            if not rel_id:
                continue
            part = doc.part.related_parts.get(rel_id)
            payload = getattr(part, "blob", None) if part is not None else None
            if not payload:
                continue

            part_name = str(getattr(part, "partname", "") or "")
            content_type = str(getattr(part, "content_type", "") or "")
            image_ext = _guess_image_ext(content_type, part_name)
            image_ref = f"images/{uuid.uuid4().hex}.{image_ext}"
            marker = f"__DOCX_IMAGE_{image_idx}__"
            image_idx += 1

            text_before_image = "".join(paragraph_text_parts).strip()
            if text_before_image:
                content_sequence.append(("text", text_before_image))
            paragraph_text_parts = []

            content_sequence.append(("image", marker))
            image_replacement_mapping[marker] = image_ref
            extracted_images.append((image_ref, payload, image_ext))

        paragraph_text = "".join(paragraph_text_parts).strip()
        if paragraph_text:
            content_sequence.append(("text", paragraph_text))

    markdown = _build_markdown_with_image_mapping(content_sequence, image_replacement_mapping).strip()
    if not markdown:
        markdown = _extract_docx_markdown(docx_bytes)
    return markdown, extracted_images


def _extract_pdf_markdown(pdf_bytes: bytes) -> str:
    # Primary path: use MarkItDown and keep inline image data URIs so we can
    # preserve image position in markdown.
    try:
        markdown = convert_file_to_markdown(pdf_bytes, ".pdf", keep_data_uris=True)
        if markdown and markdown.strip():
            return markdown
    except Exception:
        pass

    # Fallback path keeps legacy behavior for text-only extraction.
    try:
        return _extract_pdf_text_pymupdf(pdf_bytes)
    except Exception:
        try:
            return _extract_pdf_text_pdfplumber(pdf_bytes)
        except Exception as exc:
            raise ParseError("corrupted_file", f"pdf parse failed: {exc}")


def _extract_pdf_page_images_pymupdf(pdf_bytes: bytes) -> List[List[Tuple[bytes, str]]]:
    import fitz  # type: ignore

    pages: List[List[Tuple[bytes, str]]] = []
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page in doc:
            image_items: List[Tuple[bytes, str]] = []
            images = page.get_images(full=True)
            for img in images:
                try:
                    xref = img[0]
                    extracted = doc.extract_image(xref)
                    payload = extracted.get("image")
                    if not payload:
                        continue
                    ext = str(extracted.get("ext") or "png").lower()
                    image_items.append((payload, ext))
                except Exception:
                    continue
            pages.append(image_items)
    return pages


def _compose_markdown_pages_with_images(
    page_texts: List[str],
    page_images: List[List[Tuple[bytes, str]]],
) -> Tuple[str, List[Tuple[str, bytes, str]]]:
    refs: List[Tuple[str, bytes, str]] = []
    if not page_images:
        return "\n\n\f\n\n".join((t or "").strip() for t in page_texts if (t or "").strip()), refs

    max_pages = max(len(page_texts), len(page_images))
    if max_pages <= 0:
        max_pages = 1

    merged_pages: List[str] = []
    for page_idx in range(max_pages):
        text = (page_texts[page_idx] if page_idx < len(page_texts) else "") or ""
        images = page_images[page_idx] if page_idx < len(page_images) else []
        blocks: List[str] = []
        if text.strip():
            blocks.append(text.strip())
        for payload, ext in images:
            image_ref = f"images/{uuid.uuid4().hex}.{ext}"
            refs.append((image_ref, payload, ext))
            blocks.append(f"![]({image_ref})")
        page_md = "\n\n".join(blocks).strip()
        if page_md:
            merged_pages.append(page_md)
    return "\n\n\f\n\n".join(merged_pages), refs


def _inject_pdf_fallback_images(markdown: str, pdf_bytes: bytes) -> Tuple[str, List[Tuple[str, bytes, str]]]:
    page_images = _extract_pdf_page_images_pymupdf(pdf_bytes)
    has_any_image = any(items for items in page_images)
    if not has_any_image:
        return markdown, []

    page_texts = [seg.strip() for seg in (markdown or "").split("\f")]
    if len(page_texts) < len(page_images):
        page_texts = _extract_pdf_page_texts_pymupdf(pdf_bytes)

    composed, refs = _compose_markdown_pages_with_images(page_texts, page_images)
    if composed.strip():
        return composed, refs
    return markdown, refs


def _extract_text(source_bytes: bytes, ext: str) -> str:
    if ext == ".pdf":
        return _extract_pdf_markdown(source_bytes)

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


def _file_name_from_ref(original_ref: str, ext: str) -> str:
    raw_ref = (original_ref or "").strip()
    if raw_ref:
        parsed = urlparse(raw_ref)
        candidate = os.path.basename(parsed.path or raw_ref)
        if candidate:
            return candidate
    safe_ext = (ext or "").lower().strip(".") or "bin"
    return f"image-{uuid.uuid4().hex}.{safe_ext}"


_MARKDOWN_DATA_URI_IMAGE_PATTERN = re.compile(
    r"!\[([^\]]*)\]\(\s*<?data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)>?\s*\)",
    flags=re.IGNORECASE,
)

_HTML_DATA_URI_IMAGE_PATTERN = re.compile(
    r"(<img\b[^>]*\bsrc\s*=\s*)([\"'])data:image/([a-zA-Z0-9.+-]+);base64,([^\"']+)\2([^>]*>)",
    flags=re.IGNORECASE,
)


def _decode_base64_payload(raw_b64: str) -> bytes:
    if not raw_b64:
        return b""
    normalized = re.sub(r"\s+", "", raw_b64)
    try:
        return base64.b64decode(normalized)
    except Exception:
        return b""


def _extract_markdown_data_uri_images(markdown: str) -> Tuple[str, List[Tuple[str, bytes, str]]]:
    if not markdown:
        return "", []

    refs: List[Tuple[str, bytes, str]] = []

    def _replace_markdown(match: re.Match) -> str:
        alt_text = match.group(1) or ""
        image_ext = (match.group(2) or "png").lower()
        payload = _decode_base64_payload(match.group(3) or "")
        if not payload:
            return match.group(0)

        image_ref = f"images/{uuid.uuid4().hex}.{image_ext}"
        refs.append((image_ref, payload, image_ext))
        return f"![{alt_text}]({image_ref})"

    def _replace_html(match: re.Match) -> str:
        prefix = match.group(1) or ""
        quote = match.group(2) or "\""
        image_ext = (match.group(3) or "png").lower()
        payload = _decode_base64_payload(match.group(4) or "")
        suffix = match.group(5) or ">"
        if not payload:
            return match.group(0)

        image_ref = f"images/{uuid.uuid4().hex}.{image_ext}"
        refs.append((image_ref, payload, image_ext))
        return f"{prefix}{quote}{image_ref}{quote}{suffix}"

    replaced = _MARKDOWN_DATA_URI_IMAGE_PATTERN.sub(_replace_markdown, markdown)
    replaced = _HTML_DATA_URI_IMAGE_PATTERN.sub(_replace_html, replaced)
    return replaced, refs


def _to_error_string(code: str, message: str) -> str:
    safe_code = (code or "service_unavailable").strip() or "service_unavailable"
    safe_message = (message or "").strip()
    return f"{safe_code}: {safe_message}" if safe_message else safe_code


def _build_image_refs(extracted_images: List[Tuple[str, bytes, str]]) -> List[ImageRef]:
    refs: List[ImageRef] = []
    for original_ref, payload, ext in extracted_images:
        safe_bytes = payload or b""
        refs.append(
            ImageRef(
                original_ref=original_ref,
                file_name=_file_name_from_ref(original_ref, ext),
                mime_type=_content_type_from_ext(ext),
                bytes_base64=base64.b64encode(safe_bytes).decode("ascii") if safe_bytes else None,
            )
        )
    return refs


async def read_document(job_id: str, file_url: str, options: dict) -> ReadResponse:
    safe_options = options or {}
    user_id = _safe_token(str(safe_options.get("user_id") or safe_options.get("userId") or "anonymous"), "anonymous")
    source_bytes, ext = await asyncio.to_thread(_read_source_bytes, file_url)
    inline_images: List[Tuple[str, bytes, str]] = []
    if ext == ".docx":
        text, inline_images = await asyncio.to_thread(_extract_docx_markdown_and_images, source_bytes)
    else:
        text = await asyncio.to_thread(_extract_text, source_bytes, ext)
        if ext == ".pdf":
            text, inline_images = await asyncio.to_thread(_extract_markdown_data_uri_images, text)
            if not inline_images:
                text, inline_images = await asyncio.to_thread(_inject_pdf_fallback_images, text, source_bytes)

    if not text.strip():
        raise ParseError("corrupted_file", "empty extracted text")
    metadata: Dict[str, str] = {
        "source_ext": ext or "unknown",
        "user_id": user_id,
        "job_id": (job_id or "").strip(),
    }
    return ReadResponse(
        markdown_content=text.strip(),
        image_refs=_build_image_refs(inline_images),
        metadata=metadata,
        error="",
    )


async def read_document_safe(job_id: str, file_url: str, options: dict) -> ReadResponse:
    try:
        return await read_document(job_id, file_url, options)
    except ParseError as exc:
        return ReadResponse(
            markdown_content="",
            image_refs=[],
            metadata={"job_id": (job_id or "").strip()},
            error=_to_error_string(exc.code, exc.message),
        )
    except Exception as exc:
        return ReadResponse(
            markdown_content="",
            image_refs=[],
            metadata={"job_id": (job_id or "").strip()},
            error=_to_error_string("service_unavailable", str(exc)),
        )
