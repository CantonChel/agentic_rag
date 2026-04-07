"""Package loading helpers for the benchmark runner."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from datetime import timezone
from pathlib import Path
from typing import Any
from typing import Iterable
from typing import List

from .contracts import AuthoringBlock
from .contracts import BenchmarkSample
from .contracts import BlockLink
from .contracts import GoldBlockReference
from .contracts import GoldPackageManifest
from .contracts import SampleGenerationTrace
from .contracts import SourceFileRecord
from .contracts import SourceManifest
from .legacy_import import load_legacy_dataset
from .normalizer import NormalizedBlock
from .normalizer import NormalizedDocument
from .package_spec import STANDARD_PACKAGE_FILES
from .validator import validate_package_dir


@dataclass(frozen=True)
class LoadedBenchmarkPackage:
    """Materialized package contents used by the benchmark runner."""

    package_dir: Path
    source_manifest: SourceManifest
    normalized_documents: List[NormalizedDocument]
    authoring_blocks: List[AuthoringBlock]
    block_links: List[BlockLink]
    benchmark_samples: List[BenchmarkSample]
    sample_generation_traces: List[SampleGenerationTrace]
    manifest: GoldPackageManifest

    @property
    def gold_package_manifest(self) -> GoldPackageManifest:
        return self.manifest

    @property
    def gold_block_count(self) -> int:
        return len(self.authoring_blocks)


def load_benchmark_input(package_dir: Path | None = None, legacy_dataset: Path | None = None) -> LoadedBenchmarkPackage:
    """Load either a standard package or a legacy dataset."""

    if package_dir and legacy_dataset:
        raise ValueError("package_dir and legacy_dataset are mutually exclusive")
    if package_dir:
        return load_benchmark_package(package_dir)
    if legacy_dataset:
        return load_legacy_benchmark_input(legacy_dataset)
    raise ValueError("Either package_dir or legacy_dataset must be provided")


def load_benchmark_package(package_dir: Path) -> LoadedBenchmarkPackage:
    """Validate and load one portable benchmark package from disk."""

    validation = validate_package_dir(package_dir)
    if not validation.ok:
        errors = [message.message for message in validation.messages if message.level == "error"]
        raise ValueError("; ".join(errors) or f"Invalid package directory: {package_dir}")

    source_manifest_path = package_dir / STANDARD_PACKAGE_FILES["source_manifest"]
    normalized_documents_path = package_dir / STANDARD_PACKAGE_FILES["normalized_documents"]
    authoring_blocks_path = package_dir / STANDARD_PACKAGE_FILES["authoring_blocks"]
    block_links_path = package_dir / STANDARD_PACKAGE_FILES["block_links"]
    samples_path = package_dir / STANDARD_PACKAGE_FILES["samples"]
    sample_generation_trace_path = package_dir / STANDARD_PACKAGE_FILES["sample_generation_trace"]
    manifest_path = package_dir / STANDARD_PACKAGE_FILES["gold_package_manifest"]

    return LoadedBenchmarkPackage(
        package_dir=package_dir,
        source_manifest=parse_source_manifest(json.loads(source_manifest_path.read_text(encoding="utf-8"))),
        normalized_documents=[parse_normalized_document(item) for item in read_jsonl(normalized_documents_path)],
        authoring_blocks=[parse_authoring_block(item) for item in read_jsonl(authoring_blocks_path)],
        block_links=[parse_block_link(item) for item in read_jsonl(block_links_path)],
        benchmark_samples=[parse_benchmark_sample(item) for item in read_jsonl(samples_path)],
        sample_generation_traces=[
            parse_sample_generation_trace(item) for item in read_jsonl(sample_generation_trace_path)
        ],
        manifest=parse_gold_package_manifest(json.loads(manifest_path.read_text(encoding="utf-8"))),
    )


def load_legacy_benchmark_input(dataset_path: Path) -> LoadedBenchmarkPackage:
    """Load one legacy dataset and wrap it into the runner's common input shape."""

    resolved_dataset = dataset_path.expanduser().resolve()
    created_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    samples = load_legacy_dataset(resolved_dataset)
    source_manifest = SourceManifest(
        source_set_id=hashlib.sha1(str(resolved_dataset).encode("utf-8")).hexdigest()[:24],
        project_key="legacy_dataset",
        source_root=str(resolved_dataset.parent),
        file_count=1,
        files=[
            SourceFileRecord(
                path=resolved_dataset.name,
                size_bytes=resolved_dataset.stat().st_size,
                sha256=file_sha256(resolved_dataset),
            )
        ],
        created_at=created_at,
    )
    return LoadedBenchmarkPackage(
        package_dir=resolved_dataset.parent,
        source_manifest=source_manifest,
        normalized_documents=[],
        authoring_blocks=[],
        block_links=[],
        benchmark_samples=samples,
        sample_generation_traces=[],
        manifest=GoldPackageManifest(
            package_version="legacy_import_v1",
            project_key="legacy_dataset",
            suite_version="legacy_import_v1",
            created_at=created_at,
            generator_version="legacy_import",
            files={},
        ),
    )


def read_jsonl(file_path: Path) -> List[dict[str, Any]]:
    """Read one JSONL file into a list of dictionaries."""

    rows: List[dict[str, Any]] = []
    for line_no, line in enumerate(file_path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text:
            continue
        payload = json.loads(text)
        if not isinstance(payload, dict):
            raise ValueError(f"{file_path.name}:{line_no} must be a JSON object")
        rows.append(payload)
    return rows


def parse_source_manifest(payload: dict[str, Any]) -> SourceManifest:
    return SourceManifest(
        source_set_id=str(payload["source_set_id"]),
        project_key=str(payload["project_key"]),
        source_root=str(payload["source_root"]),
        file_count=int(payload["file_count"]),
        files=[parse_source_file_record(item) for item in payload.get("files", []) if isinstance(item, dict)],
        created_at=str(payload["created_at"]),
    )


def parse_source_file_record(payload: dict[str, Any]) -> SourceFileRecord:
    return SourceFileRecord(
        path=str(payload["path"]),
        size_bytes=int(payload["size_bytes"]),
        sha256=str(payload["sha256"]),
    )


def parse_gold_package_manifest(payload: dict[str, Any]) -> GoldPackageManifest:
    return GoldPackageManifest(
        package_version=str(payload["package_version"]),
        project_key=str(payload["project_key"]),
        suite_version=str(payload["suite_version"]),
        created_at=str(payload["created_at"]),
        generator_version=str(payload["generator_version"]),
        files=dict(payload["files"]),
    )


def parse_normalized_document(payload: dict[str, Any]) -> NormalizedDocument:
    metadata = payload.get("metadata")
    if not isinstance(metadata, dict):
        metadata = {}
    return NormalizedDocument(
        doc_path=str(payload["doc_path"]),
        title=str(payload["title"]),
        normalized_text=str(payload["normalized_text"]),
        metadata={str(key): str(value) for key, value in metadata.items()},
        blocks=[parse_normalized_block(item) for item in payload.get("blocks", []) if isinstance(item, dict)],
    )


def parse_normalized_block(payload: dict[str, Any]) -> NormalizedBlock:
    return NormalizedBlock(
        block_id=str(payload["block_id"]),
        section_key=str(payload["section_key"]),
        section_title=str(payload["section_title"]),
        block_type=str(payload["block_type"]),
        heading_level=int(payload["heading_level"]),
        content=str(payload["content"]),
        start_line=int(payload["start_line"]),
        end_line=int(payload["end_line"]),
    )


def parse_authoring_block(payload: dict[str, Any]) -> AuthoringBlock:
    return AuthoringBlock(
        block_id=str(payload["block_id"]),
        doc_path=str(payload["doc_path"]),
        section_key=str(payload["section_key"]),
        section_title=str(payload["section_title"]),
        block_type=str(payload["block_type"]),
        heading_level=int(payload["heading_level"]),
        text=str(payload["text"]),
        anchor=str(payload["anchor"]),
        source_hash=str(payload["source_hash"]),
        start_line=int(payload["start_line"]),
        end_line=int(payload["end_line"]),
    )


def parse_block_link(payload: dict[str, Any]) -> BlockLink:
    return BlockLink(
        from_block_id=str(payload["from_block_id"]),
        to_block_id=str(payload["to_block_id"]),
        link_type=str(payload["link_type"]),
    )


def parse_sample_generation_trace(payload: dict[str, Any]) -> SampleGenerationTrace:
    return SampleGenerationTrace(
        sample_id=str(payload["sample_id"]),
        generation_method=str(payload["generation_method"]),
        input_block_ids=normalize_text_list(payload.get("input_block_ids")),
        generator_version=str(payload["generator_version"]),
        model_or_rule_name=str(payload["model_or_rule_name"]),
        validation_status=str(payload["validation_status"]),
    )


def parse_benchmark_sample(payload: dict[str, Any]) -> BenchmarkSample:
    return BenchmarkSample(
        sample_id=str(payload["sample_id"]),
        question=str(payload["question"]),
        ground_truth=str(payload["ground_truth"]),
        ground_truth_contexts=normalize_text_list(payload.get("ground_truth_contexts")),
        gold_block_refs=parse_gold_block_references(
            payload.get("gold_block_refs"),
            fallback_items=payload.get("gold_evidence_refs"),
        ),
        tags=normalize_text_list(payload.get("tags")),
        difficulty=str(payload.get("difficulty") or "medium"),
        suite_version=str(payload.get("suite_version") or "v1"),
    )


def parse_gold_block_references(items: Any, fallback_items: Any | None = None) -> List[GoldBlockReference]:
    resolved_items = items
    if not isinstance(resolved_items, Iterable) or isinstance(resolved_items, (str, bytes, dict)):
        resolved_items = fallback_items
    if not isinstance(resolved_items, Iterable) or isinstance(resolved_items, (str, bytes, dict)):
        return []
    out: List[GoldBlockReference] = []
    for item in resolved_items:
        if not isinstance(item, dict):
            continue
        block_id = str(item.get("block_id") or item.get("evidence_id") or "").strip()
        doc_path = str(item.get("doc_path") or "").strip()
        section_key = str(item.get("section_key") or "").strip()
        if not (block_id and doc_path and section_key):
            continue
        out.append(GoldBlockReference(block_id=block_id, doc_path=doc_path, section_key=section_key))
    return out


def normalize_text_list(items: Any) -> List[str]:
    if not isinstance(items, list):
        return []
    out = [str(item).strip() for item in items]
    return [item for item in out if item]


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()
