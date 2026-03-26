"""Document normalization based on docreader markdown output."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict
from typing import List
import hashlib
import re

from .docreader_client import DocreaderReadResult
from .docreader_client import DocreaderServiceClient


HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
INLINE_WHITESPACE_PATTERN = re.compile(r"[ \t]+$")
UNSUPPORTED_CHAR_PATTERN = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F]")
SLUG_PATTERN = re.compile(r"[^\w]+", re.UNICODE)


@dataclass(frozen=True)
class NormalizedBlock:
    block_id: str
    section_key: str
    section_title: str
    block_type: str
    heading_level: int
    content: str
    start_line: int
    end_line: int

    def to_dict(self) -> Dict[str, object]:
        return {
            "block_id": self.block_id,
            "section_key": self.section_key,
            "section_title": self.section_title,
            "block_type": self.block_type,
            "heading_level": self.heading_level,
            "content": self.content,
            "start_line": self.start_line,
            "end_line": self.end_line,
        }


@dataclass(frozen=True)
class NormalizedDocument:
    doc_path: str
    title: str
    normalized_text: str
    metadata: Dict[str, str]
    blocks: List[NormalizedBlock]

    def to_dict(self) -> Dict[str, object]:
        return {
            "doc_path": self.doc_path,
            "title": self.title,
            "normalized_text": self.normalized_text,
            "metadata": dict(self.metadata),
            "blocks": [block.to_dict() for block in self.blocks],
        }


class DocumentNormalizer:
    """Normalize documents into stable markdown text and section blocks."""

    def __init__(self, docreader_client: DocreaderServiceClient) -> None:
        self.docreader_client = docreader_client

    def normalize_path(
        self,
        source_path: Path,
        source_root: Path | None = None,
        pipeline_version: str = "v1",
    ) -> NormalizedDocument:
        result = self.docreader_client.read_path_file(source_path, pipeline_version=pipeline_version)
        return self.normalize_docreader_result(source_path, result, source_root=source_root)

    def normalize_docreader_result(
        self,
        source_path: Path,
        result: DocreaderReadResult,
        source_root: Path | None = None,
    ) -> NormalizedDocument:
        resolved_source = source_path.expanduser().resolve()
        doc_path = relativize_path(resolved_source, source_root)
        normalized_text = sanitize_markdown(result.markdown_content)
        title = derive_title(doc_path, normalized_text)
        metadata = normalize_metadata(result.metadata, resolved_source)
        blocks = split_markdown_into_blocks(doc_path, title, normalized_text)
        return NormalizedDocument(
            doc_path=doc_path,
            title=title,
            normalized_text=normalized_text,
            metadata=metadata,
            blocks=blocks,
        )


def sanitize_markdown(markdown: str) -> str:
    text = str(markdown or "")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = UNSUPPORTED_CHAR_PATTERN.sub("", text)
    lines = [INLINE_WHITESPACE_PATTERN.sub("", line) for line in text.split("\n")]
    sanitized = "\n".join(lines).strip()
    return sanitized


def normalize_metadata(metadata: Dict[str, str], source_path: Path) -> Dict[str, str]:
    out = {str(key): str(value) for key, value in (metadata or {}).items()}
    out["source_path"] = str(source_path)
    out["source_name"] = source_path.name
    return out


def relativize_path(source_path: Path, source_root: Path | None = None) -> str:
    if source_root is not None:
        resolved_root = source_root.expanduser().resolve()
        try:
            return source_path.relative_to(resolved_root).as_posix()
        except ValueError:
            pass
    return source_path.name


def derive_title(doc_path: str, markdown: str) -> str:
    for line in markdown.split("\n"):
        match = HEADING_PATTERN.match(line.strip())
        if match and match.group(1) == "#":
            return match.group(2).strip()
    return Path(doc_path).stem


def split_markdown_into_blocks(doc_path: str, title: str, markdown: str) -> List[NormalizedBlock]:
    if not markdown:
        return []

    blocks: List[NormalizedBlock] = []
    lines = markdown.split("\n")
    seen_section_keys: Dict[str, int] = {}
    current_title = title
    current_section_key = "root"
    current_level = 0
    current_start_line = 1
    current_lines: List[str] = []

    def flush(end_line: int) -> None:
        nonlocal current_lines
        content = "\n".join(current_lines).strip()
        if not content:
            current_lines = []
            return
        block_type = classify_block_type(current_title, current_level)
        block_id = stable_block_id(doc_path, current_section_key, content)
        blocks.append(
            NormalizedBlock(
                block_id=block_id,
                section_key=current_section_key,
                section_title=current_title,
                block_type=block_type,
                heading_level=current_level,
                content=content,
                start_line=current_start_line,
                end_line=end_line,
            )
        )
        current_lines = []

    for line_no, raw_line in enumerate(lines, start=1):
        stripped = raw_line.strip()
        heading_match = HEADING_PATTERN.match(stripped)
        if heading_match:
            flush(line_no - 1)
            current_level = len(heading_match.group(1))
            current_title = heading_match.group(2).strip() or title
            current_section_key = dedupe_section_key(slugify(current_title), seen_section_keys)
            current_start_line = line_no
            current_lines = [stripped]
            continue

        if not current_lines and stripped == "":
            continue
        if not current_lines:
            current_start_line = line_no
        current_lines.append(raw_line)

    flush(len(lines))
    return blocks


def classify_block_type(section_title: str, heading_level: int) -> str:
    lowered = section_title.strip().lower()
    if heading_level == 0:
        return "document"
    if "response" in lowered or "返回" in section_title:
        return "response_section"
    if "request body" in lowered or "请求体" in section_title:
        return "request_section"
    if "header" in lowered or "请求头" in section_title:
        return "header_section"
    if "query" in lowered or "查询参数" in section_title:
        return "query_section"
    return "section"


def slugify(text: str) -> str:
    lowered = text.strip().lower()
    slug = SLUG_PATTERN.sub("_", lowered).strip("_")
    return slug or "section"


def dedupe_section_key(base_key: str, seen: Dict[str, int]) -> str:
    count = seen.get(base_key, 0) + 1
    seen[base_key] = count
    if count == 1:
        return base_key
    return f"{base_key}_{count}"


def stable_block_id(doc_path: str, section_key: str, content: str) -> str:
    seed = f"{doc_path}|{section_key}|{content}"
    return hashlib.sha1(seed.encode("utf-8")).hexdigest()[:16]
