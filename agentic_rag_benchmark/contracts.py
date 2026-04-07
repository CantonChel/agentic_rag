"""Core contract models for generic benchmark assets."""

from __future__ import annotations

from dataclasses import asdict
from dataclasses import dataclass
from dataclasses import field
from typing import Any
from typing import Dict
from typing import List
from typing import Optional


@dataclass(frozen=True)
class EvidenceReference:
    """Legacy evidence reference kept for staged compatibility."""

    evidence_id: str
    doc_path: str
    section_key: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class EvidenceUnit:
    """Legacy evidence unit kept for staged compatibility."""

    evidence_id: str
    doc_path: str
    section_key: str
    section_title: str
    canonical_text: str
    anchor: str
    source_hash: str
    extractor_version: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class SourceFileRecord:
    path: str
    size_bytes: int
    sha256: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class SourceManifest:
    source_set_id: str
    project_key: str
    source_root: str
    file_count: int
    files: List[SourceFileRecord]
    created_at: str

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["files"] = [item.to_dict() for item in self.files]
        return payload


@dataclass(frozen=True)
class AuthoringBlock:
    block_id: str
    doc_path: str
    section_key: str
    section_title: str
    block_type: str
    heading_level: int
    text: str
    anchor: str
    source_hash: str
    start_line: int
    end_line: int

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class BlockLink:
    from_block_id: str
    to_block_id: str
    link_type: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class GoldBlockReference:
    block_id: str
    doc_path: str
    section_key: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class SampleGenerationTrace:
    sample_id: str
    generation_method: str
    input_block_ids: List[str]
    generator_version: str
    model_or_rule_name: str
    validation_status: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True, init=False)
class BenchmarkSample:
    """Portable gold sample used by benchmark runners."""

    sample_id: str
    question: str
    ground_truth: str
    ground_truth_contexts: List[str]
    gold_block_refs: List[GoldBlockReference]
    tags: List[str] = field(default_factory=list)
    difficulty: str = "medium"
    suite_version: str = "v1"

    def __init__(
        self,
        sample_id: str,
        question: str,
        ground_truth: str,
        ground_truth_contexts: List[str],
        gold_block_refs: List[GoldBlockReference] | None = None,
        tags: List[str] | None = None,
        difficulty: str = "medium",
        suite_version: str = "v1",
        gold_evidence_refs: List[EvidenceReference] | None = None,
    ) -> None:
        resolved_refs = list(gold_block_refs or [])
        if not resolved_refs and gold_evidence_refs:
            resolved_refs = [
                GoldBlockReference(
                    block_id=ref.evidence_id,
                    doc_path=ref.doc_path,
                    section_key=ref.section_key,
                )
                for ref in gold_evidence_refs
            ]
        object.__setattr__(self, "sample_id", sample_id)
        object.__setattr__(self, "question", question)
        object.__setattr__(self, "ground_truth", ground_truth)
        object.__setattr__(self, "ground_truth_contexts", list(ground_truth_contexts))
        object.__setattr__(self, "gold_block_refs", resolved_refs)
        object.__setattr__(self, "tags", list(tags or []))
        object.__setattr__(self, "difficulty", difficulty)
        object.__setattr__(self, "suite_version", suite_version)

    @property
    def gold_evidence_refs(self) -> List[EvidenceReference]:
        return [
            EvidenceReference(
                evidence_id=ref.block_id,
                doc_path=ref.doc_path,
                section_key=ref.section_key,
            )
            for ref in self.gold_block_refs
        ]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "sample_id": self.sample_id,
            "question": self.question,
            "ground_truth": self.ground_truth,
            "ground_truth_contexts": list(self.ground_truth_contexts),
            "gold_block_refs": [ref.to_dict() for ref in self.gold_block_refs],
            "tags": list(self.tags),
            "difficulty": self.difficulty,
            "suite_version": self.suite_version,
        }


@dataclass(frozen=True)
class BuildDescriptor:
    """Description of one benchmarkable knowledge-base build."""

    build_id: str
    source_snapshot_id: str
    chunk_strategy_version: str
    embedding_model: str
    retriever_config: Dict[str, Any]
    status: str
    created_at: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class TurnExecutionSummary:
    """Structured result for one real single-turn execution."""

    session_id: str
    turn_id: str
    user_question: str
    final_answer: str
    finish_reason: str
    tool_calls: List[Dict[str, Any]]
    retrieval_trace_ids: List[str]
    latency_ms: int
    provider: str
    build_id: Optional[str]
    thinking_profile: Optional[str]
    memory_enabled: bool

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class SuiteManifest:
    """Legacy suite manifest kept for staged compatibility."""

    package_version: str
    project_key: str
    suite_version: str
    created_at: str
    generator_version: str
    files: Dict[str, str]

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class GoldPackageManifest:
    package_version: str
    project_key: str
    suite_version: str
    created_at: str
    generator_version: str
    files: Dict[str, str]

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)
