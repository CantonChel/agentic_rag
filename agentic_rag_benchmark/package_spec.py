"""Portable benchmark package layout and helper constants."""

from __future__ import annotations

from pathlib import Path
from typing import Dict


PACKAGE_VERSION = "v1"
STANDARD_PACKAGE_FILES: Dict[str, str] = {
    "evidence_units": "evidence_units.jsonl",
    "benchmark_suite": "benchmark_suite.jsonl",
    "suite_manifest": "suite_manifest.json",
    "review_markdown": "benchmark_suite.md",
}


def build_package_dir(root_dir: Path, project_key: str, suite_version: str) -> Path:
    """Return the standard package directory for one project and suite version."""

    return root_dir / "packages" / project_key / suite_version
