"""Portable benchmark package layout and helper constants."""

from __future__ import annotations

from pathlib import Path
from typing import Dict


PACKAGE_VERSION = "v1"
STANDARD_PACKAGE_FILES: Dict[str, str] = {
    "source_manifest": "source_manifest.json",
    "normalized_documents": "normalized_documents.jsonl",
    "authoring_blocks": "authoring_blocks.jsonl",
    "block_links": "block_links.jsonl",
    "samples": "samples.jsonl",
    "sample_generation_trace": "sample_generation_trace.jsonl",
    "gold_package_manifest": "gold_package_manifest.json",
    "review_markdown": "review.md",
}


def build_package_dir(root_dir: Path, project_key: str, suite_version: str) -> Path:
    """Return the standard package directory for one project and suite version."""

    return root_dir / "packages" / project_key / suite_version
