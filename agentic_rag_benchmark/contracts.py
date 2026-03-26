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
    """Stable reference from a benchmark sample to one evidence unit."""

    evidence_id: str
    doc_path: str
    section_key: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class EvidenceUnit:
    """Canonical evidence block derived from a normalized document."""

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
class BenchmarkSample:
    """Portable gold sample used by benchmark runners."""

    sample_id: str
    question: str
    ground_truth: str
    ground_truth_contexts: List[str]
    gold_evidence_refs: List[EvidenceReference]
    tags: List[str] = field(default_factory=list)
    difficulty: str = "medium"
    suite_version: str = "v1"

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["gold_evidence_refs"] = [ref.to_dict() for ref in self.gold_evidence_refs]
        return payload


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

