import os
import tempfile
from typing import Any


def _extract_markdown_text(result: Any) -> str:
    if result is None:
        return ""
    if isinstance(result, str):
        return result

    for field in ("text_content", "markdown", "text", "content"):
        value = getattr(result, field, None)
        if isinstance(value, str):
            return value

    return str(result)


def _normalize_ext(file_ext: str) -> str:
    ext = (file_ext or "").strip().lower()
    if ext and not ext.startswith("."):
        ext = "." + ext
    return ext


def convert_file_to_markdown(file_bytes: bytes, file_ext: str, keep_data_uris: bool = False) -> str:
    if not file_bytes:
        return ""

    try:
        from markitdown import MarkItDown  # type: ignore
    except Exception as exc:
        raise RuntimeError(f"markitdown import failed: {exc}")

    temp_path = ""
    try:
        suffix = _normalize_ext(file_ext) or ".bin"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(file_bytes)
            temp_path = tmp.name

        converter = MarkItDown()
        try:
            # Keep inline data URI images for pdf/image-rich markdown flow.
            result = converter.convert(temp_path, keep_data_uris=keep_data_uris)
        except TypeError:
            result = converter.convert(temp_path)
        return _extract_markdown_text(result).strip()
    finally:
        if temp_path:
            try:
                os.remove(temp_path)
            except OSError:
                pass


def convert_docx_to_markdown(docx_bytes: bytes) -> str:
    return convert_file_to_markdown(docx_bytes, ".docx", keep_data_uris=False)
