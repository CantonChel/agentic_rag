import io
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

    converter = MarkItDown()
    suffix = _normalize_ext(file_ext) or ".bin"

    # Prefer stream + file_extension to align with MarkItDown best practice.
    try:
        result = converter.convert(
            io.BytesIO(file_bytes),
            file_extension=suffix,
            keep_data_uris=keep_data_uris,
        )
        return _extract_markdown_text(result).strip()
    except TypeError:
        # Backward-compatible fallback for older MarkItDown signatures.
        pass
    except Exception:
        # Fall through to tempfile-based compatibility path.
        pass

    temp_path = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(file_bytes)
            temp_path = tmp.name

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
