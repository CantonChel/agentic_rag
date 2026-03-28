"""Package loading helpers for the benchmark runner."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from typing import Iterable
from typing import List

from .contracts import BenchmarkSample
from .contracts import EvidenceReference
from .contracts import EvidenceUnit
from .contracts import SuiteManifest
from .package_spec import STANDARD_PACKAGE_FILES
from .validator import validate_package_dir


@dataclass(frozen=True)
class LoadedBenchmarkPackage:
    """Materialized package contents used by the benchmark runner."""

    package_dir: Path
    manifest: SuiteManifest
    evidence_units: List[EvidenceUnit]
    benchmark_samples: List[BenchmarkSample]


def load_benchmark_package(package_dir: Path) -> LoadedBenchmarkPackage:
    """Validate and load one portable benchmark package from disk."""

    validation = validate_package_dir(package_dir)
    if not validation.ok:
        errors = [message.message for message in validation.messages if message.level == "error"]
        raise ValueError("; ".join(errors) or f"Invalid package directory: {package_dir}")

    manifest_path = package_dir / STANDARD_PACKAGE_FILES["suite_manifest"]
    evidence_path = package_dir / STANDARD_PACKAGE_FILES["evidence_units"]
    benchmark_path = package_dir / STANDARD_PACKAGE_FILES["benchmark_suite"]

    return LoadedBenchmarkPackage(
        package_dir=package_dir,
        manifest=parse_suite_manifest(json.loads(manifest_path.read_text(encoding="utf-8"))),
        evidence_units=[parse_evidence_unit(item) for item in read_jsonl(evidence_path)],
        benchmark_samples=[parse_benchmark_sample(item) for item in read_jsonl(benchmark_path)],
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


def parse_suite_manifest(payload: dict[str, Any]) -> SuiteManifest:
    return SuiteManifest(
        package_version=str(payload["package_version"]),
        project_key=str(payload["project_key"]),
        suite_version=str(payload["suite_version"]),
        created_at=str(payload["created_at"]),
        generator_version=str(payload["generator_version"]),
        files=dict(payload["files"]),
    )


def parse_evidence_unit(payload: dict[str, Any]) -> EvidenceUnit:
    return EvidenceUnit(
        evidence_id=str(payload["evidence_id"]),
        doc_path=str(payload["doc_path"]),
        section_key=str(payload["section_key"]),
        section_title=str(payload["section_title"]),
        canonical_text=str(payload["canonical_text"]),
        anchor=str(payload["anchor"]),
        source_hash=str(payload["source_hash"]),
        extractor_version=str(payload["extractor_version"]),
    )


def parse_benchmark_sample(payload: dict[str, Any]) -> BenchmarkSample:
    return BenchmarkSample(
        sample_id=str(payload["sample_id"]),
        question=str(payload["question"]),
        ground_truth=str(payload["ground_truth"]),
        ground_truth_contexts=normalize_text_list(payload.get("ground_truth_contexts")),
        gold_evidence_refs=parse_evidence_references(payload.get("gold_evidence_refs")),
        tags=normalize_text_list(payload.get("tags")),
        difficulty=str(payload.get("difficulty") or "medium"),
        suite_version=str(payload.get("suite_version") or "v1"),
    )


def parse_evidence_references(items: Any) -> List[EvidenceReference]:
    if not isinstance(items, Iterable) or isinstance(items, (str, bytes, dict)):
        return []
    out: List[EvidenceReference] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        evidence_id = str(item.get("evidence_id") or "").strip()
        doc_path = str(item.get("doc_path") or "").strip()
        section_key = str(item.get("section_key") or "").strip()
        if not (evidence_id and doc_path and section_key):
            continue
        out.append(EvidenceReference(evidence_id=evidence_id, doc_path=doc_path, section_key=section_key))
    return out


def normalize_text_list(items: Any) -> List[str]:
    if not isinstance(items, list):
        return []
    out = [str(item).strip() for item in items]
    return [item for item in out if item]
