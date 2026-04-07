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
from typing import Tuple
from uuid import uuid4

from .contracts import BenchmarkSample
from .contracts import EvidenceReference
from .contracts import GoldBlockReference
from .progress import ProgressCallback
from .progress import emit_progress
from .runner_client import BenchmarkAppClient
from .runner_io import LoadedBenchmarkPackage
from .runner_io import load_benchmark_input


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
    gold_block_refs: List[GoldBlockReference]
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
    target_gold_block_ids: List[str] = field(default_factory=list)
    retrieved_chunk_ids: List[str] = field(default_factory=list)
    retrieved_gold_block_ids_any_stage: List[str] = field(default_factory=list)
    retrieved_gold_block_ids_context_output: List[str] = field(default_factory=list)
    target_gold_block_hit_any_stage: bool | None = None
    target_gold_block_hit_context_output: bool | None = None
    matched_stage_set: List[str] = field(default_factory=list)
    chunk_mapping_status: str = "not_attempted"
    chunk_mapping_error: str | None = None
    error: str | None = None

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
        payload = asdict(self)
        payload["gold_block_refs"] = [ref.to_dict() for ref in self.gold_block_refs]
        return payload


@dataclass(frozen=True)
class RunBenchmarkReport:
    """Prepared benchmark report object used across stage-6 runner steps."""

    request: RunBenchmarkRequest
    project_key: str
    suite_version: str
    sample_count: int
    gold_block_count: int
    started_at: str
    completed_at: str
    sample_results: List[RunBenchmarkSampleResult]

    @property
    def evidence_count(self) -> int:
        return self.gold_block_count

    def to_dict(self) -> Dict[str, Any]:
        return {
            "request": self.request.to_dict(),
            "project_key": self.project_key,
            "suite_version": self.suite_version,
            "sample_count": self.sample_count,
            "gold_block_count": self.gold_block_count,
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
    chunk_mapping_cache: Dict[Tuple[str, str], List[Dict[str, Any]]] = {}
    chunk_mapping_unavailable_errors: Dict[str, str] = {}
    for index, sample in enumerate(loaded.benchmark_samples, start=1):
        emit_progress(
            progress_callback,
            stage="running_samples",
            completed=index - 1,
            total=total_samples,
            message=f"Running sample {sample.sample_id}",
            details={"sample_id": sample.sample_id},
        )
        result = execute_sample(
            sample,
            request,
            client,
            chunk_mapping_cache=chunk_mapping_cache,
            chunk_mapping_unavailable_errors=chunk_mapping_unavailable_errors,
        )
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
    chunk_mapping_cache: Dict[Tuple[str, str], List[Dict[str, Any]]],
    chunk_mapping_unavailable_errors: Dict[str, str],
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

    mapping_analysis = analyze_gold_chunk_hits(
        sample=sample,
        build_id=request.build_id,
        retrieval_trace_records=retrieval_trace_records,
        client=client,
        chunk_mapping_cache=chunk_mapping_cache,
        chunk_mapping_unavailable_errors=chunk_mapping_unavailable_errors,
    )

    return RunBenchmarkSampleResult(
        sample_id=sample.sample_id,
        question=sample.question,
        ground_truth=sample.ground_truth,
        ground_truth_contexts=list(sample.ground_truth_contexts),
        gold_block_refs=list(sample.gold_block_refs),
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
        target_gold_block_ids=mapping_analysis.target_gold_block_ids,
        retrieved_chunk_ids=mapping_analysis.retrieved_chunk_ids,
        retrieved_gold_block_ids_any_stage=mapping_analysis.retrieved_gold_block_ids_any_stage,
        retrieved_gold_block_ids_context_output=mapping_analysis.retrieved_gold_block_ids_context_output,
        target_gold_block_hit_any_stage=mapping_analysis.target_gold_block_hit_any_stage,
        target_gold_block_hit_context_output=mapping_analysis.target_gold_block_hit_context_output,
        matched_stage_set=mapping_analysis.matched_stage_set,
        chunk_mapping_status=mapping_analysis.chunk_mapping_status,
        chunk_mapping_error=mapping_analysis.chunk_mapping_error,
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
        gold_block_refs=list(sample.gold_block_refs),
        provider=request.provider,
        build_id=request.build_id,
        session_id=session_id,
        turn_id=turn_id,
        trace_id=trace_id,
        stream_event_count=stream_event_count,
        stream_event_types=stream_event_types,
        target_gold_block_ids=ordered_unique(ref.block_id for ref in sample.gold_block_refs),
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


@dataclass(frozen=True)
class ChunkHitAnalysis:
    target_gold_block_ids: List[str]
    retrieved_chunk_ids: List[str]
    retrieved_gold_block_ids_any_stage: List[str]
    retrieved_gold_block_ids_context_output: List[str]
    target_gold_block_hit_any_stage: bool | None
    target_gold_block_hit_context_output: bool | None
    matched_stage_set: List[str]
    chunk_mapping_status: str
    chunk_mapping_error: str | None = None


def analyze_gold_chunk_hits(
    *,
    sample: BenchmarkSample,
    build_id: str,
    retrieval_trace_records: List[Dict[str, Any]],
    client: Any,
    chunk_mapping_cache: Dict[Tuple[str, str], List[Dict[str, Any]]],
    chunk_mapping_unavailable_errors: Dict[str, str],
) -> ChunkHitAnalysis:
    target_gold_block_ids = ordered_unique(ref.block_id for ref in sample.gold_block_refs)
    chunk_records = [
        record
        for record in retrieval_trace_records
        if normalize_optional_text(record.get("recordType")) == "chunk"
    ]
    retrieved_chunk_ids = ordered_unique(
        normalize_optional_text(record.get("chunkId")) for record in chunk_records
    )
    if not chunk_records:
        return ChunkHitAnalysis(
            target_gold_block_ids=target_gold_block_ids,
            retrieved_chunk_ids=[],
            retrieved_gold_block_ids_any_stage=[],
            retrieved_gold_block_ids_context_output=[],
            target_gold_block_hit_any_stage=False,
            target_gold_block_hit_context_output=False,
            matched_stage_set=[],
            chunk_mapping_status="available",
            chunk_mapping_error=None,
        )

    cached_unavailable_error = chunk_mapping_unavailable_errors.get(build_id)
    if cached_unavailable_error:
        return ChunkHitAnalysis(
            target_gold_block_ids=target_gold_block_ids,
            retrieved_chunk_ids=retrieved_chunk_ids,
            retrieved_gold_block_ids_any_stage=[],
            retrieved_gold_block_ids_context_output=[],
            target_gold_block_hit_any_stage=None,
            target_gold_block_hit_context_output=None,
            matched_stage_set=[],
            chunk_mapping_status="unavailable",
            chunk_mapping_error=cached_unavailable_error,
        )

    mappings_by_chunk: Dict[str, List[Dict[str, Any]]] = {}
    try:
        for chunk_id in retrieved_chunk_ids:
            cache_key = (build_id, chunk_id)
            if cache_key not in chunk_mapping_cache:
                chunk_mapping_cache[cache_key] = normalize_object_list(
                    client.get_build_chunk_mappings(build_id, chunk_id)
                )
            mappings_by_chunk[chunk_id] = chunk_mapping_cache[cache_key]
    except Exception as exc:
        error_message = f"Failed to fetch build chunk mappings: {exc}"
        chunk_mapping_unavailable_errors[build_id] = error_message
        return ChunkHitAnalysis(
            target_gold_block_ids=target_gold_block_ids,
            retrieved_chunk_ids=retrieved_chunk_ids,
            retrieved_gold_block_ids_any_stage=[],
            retrieved_gold_block_ids_context_output=[],
            target_gold_block_hit_any_stage=None,
            target_gold_block_hit_context_output=None,
            matched_stage_set=[],
            chunk_mapping_status="unavailable",
            chunk_mapping_error=error_message,
        )

    target_gold_block_id_set = set(target_gold_block_ids)
    any_stage_gold_block_ids: List[str] = []
    context_output_gold_block_ids: List[str] = []
    matched_stage_set: List[str] = []
    seen_matched_stages = set()
    for record in chunk_records:
        chunk_id = normalize_optional_text(record.get("chunkId"))
        if not chunk_id:
            continue
        stage = normalize_optional_text(record.get("stage")) or "unknown"
        mapped_gold_block_ids = ordered_unique(
            gold_block_id
            for mapping in mappings_by_chunk.get(chunk_id, [])
            for gold_block_id in normalize_gold_block_ids(mapping)
        )
        for gold_block_id in mapped_gold_block_ids:
            if gold_block_id not in any_stage_gold_block_ids:
                any_stage_gold_block_ids.append(gold_block_id)
        if stage == "context_output":
            for gold_block_id in mapped_gold_block_ids:
                if gold_block_id not in context_output_gold_block_ids:
                    context_output_gold_block_ids.append(gold_block_id)
        if target_gold_block_id_set.intersection(mapped_gold_block_ids) and stage not in seen_matched_stages:
            seen_matched_stages.add(stage)
            matched_stage_set.append(stage)

    return ChunkHitAnalysis(
        target_gold_block_ids=target_gold_block_ids,
        retrieved_chunk_ids=retrieved_chunk_ids,
        retrieved_gold_block_ids_any_stage=any_stage_gold_block_ids,
        retrieved_gold_block_ids_context_output=context_output_gold_block_ids,
        target_gold_block_hit_any_stage=bool(target_gold_block_id_set.intersection(any_stage_gold_block_ids)),
        target_gold_block_hit_context_output=bool(target_gold_block_id_set.intersection(context_output_gold_block_ids)),
        matched_stage_set=matched_stage_set,
        chunk_mapping_status="available",
        chunk_mapping_error=None,
    )


def normalize_gold_block_ids(mapping_payload: Dict[str, Any]) -> List[str]:
    if not isinstance(mapping_payload, dict):
        return []
    return ordered_unique(
        normalize_optional_text(item)
        for item in (
            mapping_payload.get("goldBlockIds")
            if isinstance(mapping_payload.get("goldBlockIds"), list)
            else mapping_payload.get("gold_block_ids", [])
        )
    )


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


def ordered_unique(values: Any) -> List[str]:
    out: List[str] = []
    seen = set()
    for value in values:
        normalized = normalize_optional_text(value)
        if normalized is None or normalized in seen:
            continue
        seen.add(normalized)
        out.append(normalized)
    return out


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
        gold_block_refs=list(sample.gold_block_refs),
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
        gold_block_count=len(loaded.authoring_blocks),
        started_at=started_at,
        completed_at=completed_at,
        sample_results=sample_results,
    )
