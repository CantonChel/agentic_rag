"""Import helpers for legacy benchmark datasets."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any
from typing import Dict
from typing import Iterable
from typing import List

from .contracts import BenchmarkSample
from .contracts import EvidenceReference


def load_legacy_dataset(dataset_path: Path) -> List[BenchmarkSample]:
    """Load a legacy JSONL dataset and convert it into BenchmarkSample rows."""

    if not dataset_path.exists():
        raise FileNotFoundError(f"Legacy dataset not found: {dataset_path}")

    samples: List[BenchmarkSample] = []
    for line_no, line in enumerate(dataset_path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text:
            continue
        try:
            row = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON at line {line_no}: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"Line {line_no} must be a JSON object")
        samples.append(convert_legacy_row(row, line_no))

    if not samples:
        raise ValueError("Legacy dataset has no valid rows")
    return samples


def convert_legacy_row(row: Dict[str, Any], line_no: int) -> BenchmarkSample:
    """Convert one legacy row into the canonical benchmark sample contract."""

    question = normalize_required_text(row.get("question"), "question", line_no)
    ground_truth = pick_ground_truth(row, line_no)

    sample_id = normalize_optional_text(row.get("sample_id")) or normalize_optional_text(row.get("id")) or f"legacy_{line_no:04d}"
    suite_version = normalize_optional_text(row.get("suite_version")) or "legacy_import_v1"
    difficulty = normalize_optional_text(row.get("difficulty")) or "medium"
    tags = normalize_tags(row.get("tags"))
    contexts = normalize_contexts(row.get("ground_truth_contexts"))
    refs = normalize_evidence_refs(row, sample_id)

    return BenchmarkSample(
        sample_id=sample_id,
        question=question,
        ground_truth=ground_truth,
        ground_truth_contexts=contexts,
        gold_evidence_refs=refs,
        tags=tags,
        difficulty=difficulty,
        suite_version=suite_version,
    )


def pick_ground_truth(row: Dict[str, Any], line_no: int) -> str:
    for key in ("ground_truth", "reference", "answer"):
        text = normalize_optional_text(row.get(key))
        if text:
            return text
    raise ValueError(f"Line {line_no} missing ground_truth/reference/answer")


def normalize_required_text(value: Any, field_name: str, line_no: int) -> str:
    text = normalize_optional_text(value)
    if text:
        return text
    raise ValueError(f"Line {line_no} missing {field_name}")


def normalize_optional_text(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip()
    return text


def normalize_tags(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        tags = [normalize_optional_text(item) for item in value]
        return [tag for tag in tags if tag]
    text = normalize_optional_text(value)
    if not text:
        return []
    return [text]


def normalize_contexts(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        contexts = [normalize_optional_text(item) for item in value]
        return [ctx for ctx in contexts if ctx]
    text = normalize_optional_text(value)
    return [text] if text else []


def normalize_evidence_refs(row: Dict[str, Any], sample_id: str) -> List[EvidenceReference]:
    explicit_refs = row.get("gold_evidence_refs")
    if isinstance(explicit_refs, list) and explicit_refs:
        refs = [parse_explicit_ref(item) for item in explicit_refs]
        return [ref for ref in refs if ref is not None]

    sources = extract_legacy_sources(row)
    out: List[EvidenceReference] = []
    seen = set()
    for index, source in enumerate(sources):
        doc_path = normalize_optional_text(source.get("doc_path")) or normalize_optional_text(source.get("knowledge_title")) or f"legacy/{sample_id}"
        section_key = derive_section_key(source, index)
        evidence_id = derive_evidence_id(sample_id, source, doc_path, section_key, index)
        key = (evidence_id, doc_path, section_key)
        if key in seen:
            continue
        seen.add(key)
        out.append(EvidenceReference(evidence_id=evidence_id, doc_path=doc_path, section_key=section_key))
    return out


def parse_explicit_ref(item: Any) -> EvidenceReference | None:
    if not isinstance(item, dict):
        return None
    evidence_id = normalize_optional_text(item.get("evidence_id"))
    doc_path = normalize_optional_text(item.get("doc_path"))
    section_key = normalize_optional_text(item.get("section_key"))
    if not (evidence_id and doc_path and section_key):
        return None
    return EvidenceReference(evidence_id=evidence_id, doc_path=doc_path, section_key=section_key)


def extract_legacy_sources(row: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
    meta = row.get("meta")
    if not isinstance(meta, dict):
        return []
    sources = meta.get("sources")
    if not isinstance(sources, list):
        return []
    return [item for item in sources if isinstance(item, dict)]


def derive_section_key(source: Dict[str, Any], index: int) -> str:
    chunk_index = source.get("chunk_index")
    chunk_type = normalize_optional_text(source.get("chunk_type")) or "text"
    if isinstance(chunk_index, int):
        return f"legacy_{chunk_type}_{chunk_index}"
    return f"legacy_source_{index}"


def derive_evidence_id(
    sample_id: str,
    source: Dict[str, Any],
    doc_path: str,
    section_key: str,
    index: int,
) -> str:
    knowledge_id = normalize_optional_text(source.get("knowledge_id"))
    chunk_id = normalize_optional_text(source.get("chunk_id"))
    if knowledge_id and chunk_id:
        return f"legacy_{knowledge_id}_{chunk_id}"
    seed = "|".join([sample_id, doc_path, section_key, str(index)])
    digest = hashlib.sha1(seed.encode("utf-8")).hexdigest()[:16]
    return f"legacy_{digest}"

