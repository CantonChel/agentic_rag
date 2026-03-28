"""Compose stage-2 benchmark package generation steps."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import List
import re

from .authoring import BenchmarkAuthoringStrategy
from .docreader_client import DocreaderServiceClient
from .evidence import EvidenceExtractor
from .normalizer import DocumentNormalizer
from .package_spec import build_package_dir
from .package_writer import PackageWriteResult
from .package_writer import PackageWriter


SUPPORTED_SOURCE_EXTENSIONS = {".md", ".docx", ".pdf", ".html", ".htm"}
PROJECT_KEY_PATTERN = re.compile(r"[^a-z0-9_\-]+")


@dataclass(frozen=True)
class BuildPackageReport:
    package_dir: Path
    source_files: List[Path]
    normalized_document_count: int
    evidence_count: int
    sample_count: int


def build_benchmark_package(
    source_path: Path,
    project_key: str,
    suite_version: str,
    package_root: Path,
    docreader_base_url: str,
    source_root: Path | None = None,
) -> BuildPackageReport:
    resolved_source = source_path.expanduser().resolve()
    resolved_source_root = (source_root or resolved_source).expanduser().resolve()
    if resolved_source.is_file():
        resolved_source_root = (source_root or resolved_source.parent).expanduser().resolve()

    source_files = discover_source_files(resolved_source)
    if not source_files:
        raise ValueError(f"No supported source files found under: {resolved_source}")

    sanitized_project_key = normalize_project_key(project_key)
    docreader_client = DocreaderServiceClient(base_url=docreader_base_url)
    normalizer = DocumentNormalizer(docreader_client)
    extractor = EvidenceExtractor()
    authoring = BenchmarkAuthoringStrategy(suite_version=suite_version)
    writer = PackageWriter()

    documents = [normalizer.normalize_path(path, source_root=resolved_source_root) for path in source_files]
    evidence_units = []
    for document in documents:
        evidence_units.extend(extractor.extract_document(document))
    benchmark_samples = authoring.generate_samples(evidence_units)

    package_dir = build_package_dir(package_root, sanitized_project_key, suite_version)
    write_result = writer.write_package(
        package_dir=package_dir,
        project_key=sanitized_project_key,
        suite_version=suite_version,
        evidence_units=evidence_units,
        benchmark_samples=benchmark_samples,
    )

    return BuildPackageReport(
        package_dir=write_result.package_dir,
        source_files=source_files,
        normalized_document_count=len(documents),
        evidence_count=write_result.evidence_count,
        sample_count=write_result.sample_count,
    )


def discover_source_files(source_path: Path) -> List[Path]:
    if source_path.is_file():
        return [source_path] if source_path.suffix.lower() in SUPPORTED_SOURCE_EXTENSIONS else []
    if not source_path.is_dir():
        return []
    return sorted(
        [
            path
            for path in source_path.rglob("*")
            if path.is_file() and path.suffix.lower() in SUPPORTED_SOURCE_EXTENSIONS
        ]
    )


def normalize_project_key(project_key: str) -> str:
    lowered = str(project_key or "").strip().lower()
    lowered = PROJECT_KEY_PATTERN.sub("_", lowered).strip("_")
    if not lowered:
        raise ValueError("project_key must not be empty")
    return lowered
