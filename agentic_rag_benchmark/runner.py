"""Stage-6 benchmark runner skeleton."""

from __future__ import annotations

from dataclasses import asdict
from dataclasses import dataclass
from dataclasses import field
from datetime import datetime
from datetime import timezone
from pathlib import Path
from typing import Any
from typing import Dict
from typing import List

from .contracts import BenchmarkSample
from .contracts import EvidenceReference
from .runner_io import LoadedBenchmarkPackage
from .runner_io import load_benchmark_package


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class RunBenchmarkRequest:
    """Input contract for one benchmark run."""

    package_dir: Path
    base_url: str
    provider: str
    build_id: str
    user_id: str
    session_prefix: str
    timeout_seconds: int
    output_root: Path
    verify_ssl: bool = False
    eval_mode: str = "SINGLE_TURN"
    kb_scope: str = "BENCHMARK_BUILD"
    memory_enabled: bool = False
    thinking_profile: str = "HIDE"
    tools: bool = True
    tool_choice: str = "AUTO"

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["package_dir"] = str(self.package_dir)
        payload["output_root"] = str(self.output_root)
        return payload


@dataclass(frozen=True)
class RunBenchmarkSampleResult:
    """Normalized per-sample result contract for benchmark execution."""

    sample_id: str
    question: str
    ground_truth: str
    ground_truth_contexts: List[str]
    gold_evidence_refs: List[EvidenceReference]
    provider: str
    build_id: str
    session_id: str
    turn_id: str | None = None
    trace_id: str | None = None
    final_answer: str = ""
    finish_reason: str = ""
    latency_ms: int | None = None
    tool_calls: List[Dict[str, Any]] = field(default_factory=list)
    retrieval_trace_ids: List[str] = field(default_factory=list)
    retrieval_trace_records: List[Dict[str, Any]] = field(default_factory=list)
    error: str | None = None

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["gold_evidence_refs"] = [ref.to_dict() for ref in self.gold_evidence_refs]
        return payload


@dataclass(frozen=True)
class RunBenchmarkReport:
    """Prepared benchmark report object used across stage-6 runner steps."""

    request: RunBenchmarkRequest
    project_key: str
    suite_version: str
    sample_count: int
    evidence_count: int
    started_at: str
    completed_at: str
    sample_results: List[RunBenchmarkSampleResult]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "request": self.request.to_dict(),
            "project_key": self.project_key,
            "suite_version": self.suite_version,
            "sample_count": self.sample_count,
            "evidence_count": self.evidence_count,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "sample_results": [sample.to_dict() for sample in self.sample_results],
        }


def run_benchmark(request: RunBenchmarkRequest) -> RunBenchmarkReport:
    """Prepare one benchmark run from a portable package."""

    loaded = load_benchmark_package(request.package_dir)
    started_at = utcnow_iso()
    sample_results = [
        build_pending_sample_result(
            sample=sample,
            request=request,
            session_id=f"{request.session_prefix}-{sample.sample_id}",
        )
        for sample in loaded.benchmark_samples
    ]
    completed_at = utcnow_iso()
    return build_report(request, loaded, started_at, completed_at, sample_results)


def build_pending_sample_result(
    sample: BenchmarkSample,
    request: RunBenchmarkRequest,
    session_id: str,
) -> RunBenchmarkSampleResult:
    return RunBenchmarkSampleResult(
        sample_id=sample.sample_id,
        question=sample.question,
        ground_truth=sample.ground_truth,
        ground_truth_contexts=list(sample.ground_truth_contexts),
        gold_evidence_refs=list(sample.gold_evidence_refs),
        provider=request.provider,
        build_id=request.build_id,
        session_id=session_id,
    )


def build_report(
    request: RunBenchmarkRequest,
    loaded: LoadedBenchmarkPackage,
    started_at: str,
    completed_at: str,
    sample_results: List[RunBenchmarkSampleResult],
) -> RunBenchmarkReport:
    return RunBenchmarkReport(
        request=request,
        project_key=loaded.manifest.project_key,
        suite_version=loaded.manifest.suite_version,
        sample_count=len(loaded.benchmark_samples),
        evidence_count=len(loaded.evidence_units),
        started_at=started_at,
        completed_at=completed_at,
        sample_results=sample_results,
    )
