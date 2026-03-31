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
from uuid import uuid4

from .progress import ProgressCallback
from .progress import emit_progress
from .contracts import BenchmarkSample
from .contracts import EvidenceReference
from .runner_io import LoadedBenchmarkPackage
from .runner_io import load_benchmark_input
from .runner_client import BenchmarkAppClient


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class RunBenchmarkRequest:
    """Input contract for one benchmark run."""

    package_dir: Path | None
    base_url: str
    provider: str
    build_id: str
    user_id: str
    session_prefix: str
    timeout_seconds: int
    output_root: Path
    legacy_dataset: Path | None = None
    verify_ssl: bool = False
    eval_mode: str = "SINGLE_TURN"
    kb_scope: str = "BENCHMARK_BUILD"
    memory_enabled: bool = False
    thinking_profile: str = "HIDE"
    tools: bool = True
    tool_choice: str = "AUTO"

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["package_dir"] = str(self.package_dir) if self.package_dir else None
        payload["legacy_dataset"] = str(self.legacy_dataset) if self.legacy_dataset else None
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
    stream_event_count: int = 0
    stream_event_types: Dict[str, int] = field(default_factory=dict)
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


def run_benchmark(
    request: RunBenchmarkRequest,
    client: Any | None = None,
    progress_callback: ProgressCallback | None = None,
) -> RunBenchmarkReport:
    """Prepare one benchmark run from a portable package."""

    loaded = load_benchmark_input(package_dir=request.package_dir, legacy_dataset=request.legacy_dataset)
    started_at = utcnow_iso()
    client = client or BenchmarkAppClient(
        base_url=request.base_url,
        timeout_seconds=request.timeout_seconds,
        verify_ssl=request.verify_ssl,
    )
    total_samples = len(loaded.benchmark_samples)
    emit_progress(
        progress_callback,
        stage="running_samples",
        completed=0,
        total=total_samples,
        message=f"Running {total_samples} benchmark samples",
    )
    sample_results = []
    for index, sample in enumerate(loaded.benchmark_samples, start=1):
        emit_progress(
            progress_callback,
            stage="running_samples",
            completed=index - 1,
            total=total_samples,
            message=f"Running sample {sample.sample_id}",
            details={"sample_id": sample.sample_id},
        )
        result = execute_sample(sample, request, client)
        sample_results.append(result)
        emit_progress(
            progress_callback,
            stage="running_samples",
            completed=index,
            total=total_samples,
            message=f"Completed sample {sample.sample_id}",
            details={"sample_id": sample.sample_id, "error": result.error or ""},
        )
    completed_at = utcnow_iso()
    return build_report(request, loaded, started_at, completed_at, sample_results)


def execute_sample(
    sample: BenchmarkSample,
    request: RunBenchmarkRequest,
    client: Any,
) -> RunBenchmarkSampleResult:
    session_id = build_session_id(request.session_prefix, sample.sample_id)
    try:
        stream_capture = client.stream_agent_turn(
            provider=request.provider,
            user_id=request.user_id,
            session_id=session_id,
            prompt=sample.question,
            build_id=request.build_id,
            tools=request.tools,
            tool_choice=request.tool_choice,
        )
    except Exception as exc:
        return build_error_sample_result(
            sample=sample,
            request=request,
            session_id=session_id,
            turn_id=None,
            trace_id=None,
            stream_event_count=0,
            stream_event_types={},
            error=f"Failed to stream agent turn: {exc}",
        )
    if not stream_capture.turn_id:
        return build_error_sample_result(
            sample=sample,
            request=request,
            session_id=session_id,
            turn_id=None,
            trace_id=None,
            stream_event_count=stream_capture.event_count,
            stream_event_types=dict(stream_capture.event_type_counts),
            error=stream_capture.transport_error or "Agent stream did not produce turnId",
        )

    try:
        summary = client.get_turn_summary(stream_capture.turn_id)
    except Exception as exc:
        return build_error_sample_result(
            sample=sample,
            request=request,
            session_id=session_id,
            turn_id=stream_capture.turn_id,
            trace_id=None,
            stream_event_count=stream_capture.event_count,
            stream_event_types=dict(stream_capture.event_type_counts),
            error=f"Failed to fetch turn summary: {exc}",
        )

    trace_id = normalize_optional_text(summary.get("traceId"))
    retrieval_trace_ids = merge_trace_ids(trace_id, normalize_string_list(summary.get("retrievalTraceIds")))
    retrieval_trace_records: List[Dict[str, Any]] = []
    retrieval_error: str | None = None
    if retrieval_trace_ids:
        try:
            retrieval_trace_records = collect_retrieval_trace_records(client, retrieval_trace_ids)
        except Exception as exc:
            retrieval_error = f"Failed to fetch retrieval traces: {exc}"

    return RunBenchmarkSampleResult(
        sample_id=sample.sample_id,
        question=sample.question,
        ground_truth=sample.ground_truth,
        ground_truth_contexts=list(sample.ground_truth_contexts),
        gold_evidence_refs=list(sample.gold_evidence_refs),
        provider=request.provider,
        build_id=request.build_id,
        session_id=session_id,
        turn_id=normalize_optional_text(summary.get("turnId")) or stream_capture.turn_id,
        trace_id=trace_id,
        final_answer=normalize_optional_text(summary.get("finalAnswer")) or "",
        finish_reason=normalize_optional_text(summary.get("finishReason")) or "",
        latency_ms=normalize_optional_int(summary.get("latencyMs")),
        stream_event_count=stream_capture.event_count,
        stream_event_types=dict(stream_capture.event_type_counts),
        tool_calls=normalize_object_list(summary.get("toolCalls")),
        retrieval_trace_ids=retrieval_trace_ids,
        retrieval_trace_records=retrieval_trace_records,
        error=retrieval_error or stream_capture.transport_error,
    )


def build_error_sample_result(
    sample: BenchmarkSample,
    request: RunBenchmarkRequest,
    session_id: str,
    turn_id: str | None,
    trace_id: str | None,
    stream_event_count: int,
    stream_event_types: Dict[str, int],
    error: str,
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
        turn_id=turn_id,
        trace_id=trace_id,
        stream_event_count=stream_event_count,
        stream_event_types=stream_event_types,
        error=error,
    )


def build_session_id(session_prefix: str, sample_id: str) -> str:
    return f"{session_prefix}-{sample_id}-{uuid4().hex[:8]}"


def merge_trace_ids(primary_trace_id: str | None, trace_ids: List[str]) -> List[str]:
    out: List[str] = []
    seen = set()
    for candidate in [primary_trace_id, *trace_ids]:
        if not candidate or candidate in seen:
            continue
        seen.add(candidate)
        out.append(candidate)
    return out


def collect_retrieval_trace_records(client: Any, trace_ids: List[str]) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for trace_id in trace_ids:
        records.extend(client.get_retrieval_traces(trace_id))
    return records


def normalize_optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def normalize_optional_int(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if not text:
        return None
    return int(text)


def normalize_string_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    out = [normalize_optional_text(item) for item in value]
    return [item for item in out if item is not None]


def normalize_object_list(value: Any) -> List[Dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [dict(item) for item in value if isinstance(item, dict)]


def build_pending_sample_result(
    sample: BenchmarkSample,
    request: RunBenchmarkRequest,
    session_id: str,
) -> RunBenchmarkSampleResult:
    """Backward-compatible helper kept for tests and staged evolution."""

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
