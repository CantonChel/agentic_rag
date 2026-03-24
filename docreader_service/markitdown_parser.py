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


def convert_docx_to_markdown(docx_bytes: bytes) -> str:
    if not docx_bytes:
        return ""

    try:
        from markitdown import MarkItDown  # type: ignore
    except Exception as exc:
        raise RuntimeError(f"markitdown import failed: {exc}")

    temp_path = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=".docx", delete=False) as tmp:
            tmp.write(docx_bytes)
            temp_path = tmp.name

        converter = MarkItDown()
        result = converter.convert(temp_path)
        return _extract_markdown_text(result).strip()
    finally:
        if temp_path:
            try:
                os.remove(temp_path)
            except OSError:
                pass
