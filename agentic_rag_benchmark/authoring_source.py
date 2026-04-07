"""Helpers for constructing staged gold authoring assets."""

from __future__ import annotations

from datetime import datetime
from datetime import timezone
from pathlib import Path
from typing import Iterable
from typing import List
import hashlib

from .contracts import AuthoringBlock
from .contracts import BlockLink
from .contracts import SourceFileRecord
from .contracts import SourceManifest
from .normalizer import NormalizedBlock
from .normalizer import NormalizedDocument


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_source_manifest(
    source_files: Iterable[Path],
    project_key: str,
    source_root: Path,
    created_at: str | None = None,
) -> SourceManifest:
    resolved_root = source_root.expanduser().resolve()
    records = [
        SourceFileRecord(
            path=relativize_path(path, resolved_root),
            size_bytes=path.stat().st_size,
            sha256=file_sha256(path),
        )
        for path in sorted(path.expanduser().resolve() for path in source_files)
    ]
    created_value = created_at or utcnow_iso()
    source_set_id = stable_source_set_id(project_key, resolved_root, records)
    return SourceManifest(
        source_set_id=source_set_id,
        project_key=project_key,
        source_root=str(resolved_root),
        file_count=len(records),
        files=records,
        created_at=created_value,
    )


def build_authoring_blocks(documents: Iterable[NormalizedDocument]) -> List[AuthoringBlock]:
    out: List[AuthoringBlock] = []
    for document in documents:
        for block in document.blocks:
            text = block.content.strip()
            if not text:
                continue
            out.append(
                AuthoringBlock(
                    block_id=block.block_id,
                    doc_path=document.doc_path,
                    section_key=block.section_key,
                    section_title=block.section_title,
                    block_type=block.block_type,
                    heading_level=block.heading_level,
                    text=text,
                    anchor=f"{document.doc_path}#{block.section_key}",
                    source_hash=stable_text_hash(text),
                    start_line=block.start_line,
                    end_line=block.end_line,
                )
            )
    return out


def build_block_links(documents: Iterable[NormalizedDocument]) -> List[BlockLink]:
    links: List[BlockLink] = []
    for document in documents:
        blocks = [block for block in document.blocks if block.content.strip()]
        for index, block in enumerate(blocks):
            if index > 0:
                links.append(
                    BlockLink(
                        from_block_id=blocks[index - 1].block_id,
                        to_block_id=block.block_id,
                        link_type="prev_next",
                    )
                )
            parent = find_parent_block(blocks, index)
            if parent is not None:
                links.append(
                    BlockLink(
                        from_block_id=parent.block_id,
                        to_block_id=block.block_id,
                        link_type="parent_child",
                    )
                )
    return links


def find_parent_block(blocks: List[NormalizedBlock], index: int) -> NormalizedBlock | None:
    current = blocks[index]
    if current.heading_level <= 0:
        return None
    for candidate_index in range(index - 1, -1, -1):
        candidate = blocks[candidate_index]
        if candidate.heading_level < current.heading_level:
            return candidate
    return None


def stable_text_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def relativize_path(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.name


def stable_source_set_id(project_key: str, source_root: Path, records: List[SourceFileRecord]) -> str:
    seed_parts = [project_key, str(source_root)]
    for record in records:
        seed_parts.append(f"{record.path}|{record.size_bytes}|{record.sha256}")
    seed = "\n".join(seed_parts)
    return hashlib.sha1(seed.encode("utf-8")).hexdigest()[:24]
