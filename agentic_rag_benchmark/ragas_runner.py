"""RAGAS integration for the stage-6 benchmark runner."""

from __future__ import annotations

import csv
import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from typing import Callable
from typing import Dict
from typing import List
from typing import Tuple

from .env_loader import load_dotenv_defaults
from .progress import ProgressCallback
from .progress import emit_progress
from .runner import RunBenchmarkReport
from .runner import RunBenchmarkSampleResult


RAGAS_BASE_FIELDS = {"sample_id", "question", "answer", "ground_truth", "contexts"}
DEFAULT_JUDGE_TIMEOUT_SECONDS = 60
DEFAULT_JUDGE_MAX_RETRIES = 1
DEFAULT_ROW_DIAGNOSTICS_ENABLED = True
DEFAULT_ROW_DIAGNOSTICS_LIMIT = 5
DEFAULT_JUDGE_BASE_URL = "https://api.deepseek.com/v1"
DEFAULT_JUDGE_MODEL = "deepseek-chat"


@dataclass(frozen=True)
class RagasArtifacts:
    """Files produced by one RAGAS evaluation attempt."""

    summary_path: Path | None
    per_sample_csv_path: Path | None
    error_path: Path | None
    summary: Dict[str, Any] | None = None
    error: Dict[str, Any] | None = None


def evaluate_ragas_for_report(
    report: RunBenchmarkReport,
    output_dir: Path,
    evaluator: Callable[[List[Dict[str, Any]]], Tuple[Dict[str, Any], List[Dict[str, Any]]]] | None = None,
    progress_callback: ProgressCallback | None = None,
) -> RagasArtifacts:
    """Evaluate collected benchmark results with RAGAS."""

    rows = build_ragas_rows(report.sample_results)
    summary_path = output_dir / "ragas_summary.json"
    per_sample_csv_path = output_dir / "ragas_scores_per_sample.csv"
    error_path = output_dir / "ragas_error.json"
    evaluator = evaluator or run_ragas_evaluation

    try:
        emit_progress(
            progress_callback,
            stage="evaluating_ragas",
            completed=0,
            total=1,
            message=f"Preparing RAGAS evaluation for {len(rows)} rows",
        )
        if evaluator is run_ragas_evaluation:
            summary, per_sample_rows = evaluator(rows, progress_callback=progress_callback)
        else:
            summary, per_sample_rows = evaluator(rows)
    except Exception as exc:
        payload = {
            "message": str(exc),
            "input_row_count": len(rows),
        }
        write_json(error_path, payload)
        return RagasArtifacts(
            summary_path=None,
            per_sample_csv_path=None,
            error_path=error_path,
            error=payload,
        )

    sanitized_rows = sanitize_per_sample_rows(per_sample_rows)
    write_json(summary_path, summary)
    write_csv_rows(per_sample_csv_path, sanitized_rows)
    emit_progress(
        progress_callback,
        stage="evaluating_ragas",
        completed=1,
        total=1,
        message="RAGAS evaluation completed",
    )
    return RagasArtifacts(
        summary_path=summary_path,
        per_sample_csv_path=per_sample_csv_path,
        error_path=None,
        summary=summary,
    )


def build_ragas_rows(sample_results: List[RunBenchmarkSampleResult]) -> List[Dict[str, Any]]:
    """Build RAGAS input rows from benchmark sample results."""

    rows: List[Dict[str, Any]] = []
    for sample in sample_results:
        if sample.error or not sample.final_answer.strip():
            continue
        rows.append(
            {
                "sample_id": sample.sample_id,
                "question": sample.question,
                "answer": sample.final_answer,
                "ground_truth": sample.ground_truth,
                "contexts": extract_context_output_contexts(sample),
            }
        )
    return rows


def extract_context_output_contexts(sample: RunBenchmarkSampleResult) -> List[str]:
    seen = set()
    contexts: List[str] = []
    for record in sample.retrieval_trace_records:
        if record.get("stage") != "context_output":
            continue
        if record.get("recordType") != "chunk":
            continue
        chunk_text = str(record.get("chunkText") or "").strip()
        if not chunk_text or chunk_text in seen:
            continue
        seen.add(chunk_text)
        contexts.append(chunk_text)
    return contexts or [""]


def run_ragas_evaluation(
    rows: List[Dict[str, Any]],
    progress_callback: ProgressCallback | None = None,
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """Run real RAGAS evaluation against prepared rows."""

    if not rows:
        raise RuntimeError("No valid rows available for RAGAS")

    try:
        from datasets import Dataset
        from langchain_openai import ChatOpenAI
        from ragas import evaluate
        from ragas.llms import LangchainLLMWrapper
        from ragas.metrics import context_precision
        from ragas.metrics import context_recall
        from ragas.metrics import faithfulness
    except ImportError as exc:
        raise RuntimeError("RAGAS dependencies missing. Install benchmark requirements first.") from exc

    load_dotenv_defaults(Path(__file__).resolve())
    judge_api_key = read_first_non_empty_env("RAGAS_JUDGE_API_KEY", "DEEPSEEK_API_KEY", "MINIMAX_API_KEY")
    judge_base_url = read_first_non_empty_env(
        "RAGAS_JUDGE_BASE_URL",
        "DEEPSEEK_BASE_URL",
        "MINIMAX_BASE_URL",
        default=DEFAULT_JUDGE_BASE_URL,
    )
    judge_model = read_first_non_empty_env(
        "RAGAS_JUDGE_MODEL",
        "DEEPSEEK_MODEL",
        "MINIMAX_MODEL",
        default=DEFAULT_JUDGE_MODEL,
    )
    judge_timeout_seconds = read_positive_int_env("RAGAS_JUDGE_TIMEOUT_SECONDS", DEFAULT_JUDGE_TIMEOUT_SECONDS)
    judge_max_retries = read_non_negative_int_env("RAGAS_JUDGE_MAX_RETRIES", DEFAULT_JUDGE_MAX_RETRIES)
    row_diagnostics_enabled = read_bool_env("RAGAS_ROW_DIAGNOSTICS_ENABLED", DEFAULT_ROW_DIAGNOSTICS_ENABLED)
    row_diagnostics_limit = read_non_negative_int_env("RAGAS_ROW_DIAGNOSTICS_LIMIT", DEFAULT_ROW_DIAGNOSTICS_LIMIT)
    if not judge_api_key:
        raise RuntimeError(
            "RAGAS judge API key is empty. Set RAGAS_JUDGE_API_KEY, DEEPSEEK_API_KEY, or MINIMAX_API_KEY."
        )

    judge_llm = ChatOpenAI(
        model=judge_model,
        api_key=judge_api_key,
        base_url=judge_base_url,
        temperature=0,
        timeout=judge_timeout_seconds,
        max_retries=judge_max_retries,
    )
    ragas_llm = LangchainLLMWrapper(judge_llm)

    metric_specs = [
        MetricSpec(name="faithfulness", metric=faithfulness, row_filter=lambda row: True),
    ]
    if any(bool(str(row.get("ground_truth") or "").strip()) for row in rows):
        metric_specs.extend(
            [
                MetricSpec(name="context_precision", metric=context_precision, row_filter=is_context_metric_eligible_row),
                MetricSpec(name="context_recall", metric=context_recall, row_filter=is_context_metric_eligible_row),
            ]
        )

    per_sample_rows = [dict(row) for row in rows]
    metrics_requested = [spec.name for spec in metric_specs]
    metrics_completed: List[str] = []
    metrics_failed: List[Dict[str, str]] = []
    warnings: List[str] = []
    metric_row_stats: Dict[str, Dict[str, int]] = {}
    metric_row_diagnostics: Dict[str, List[Dict[str, str]]] = {}

    total_metrics = len(metric_specs)
    emit_progress(
        progress_callback,
        stage="evaluating_ragas",
        completed=0,
        total=total_metrics,
        message=f"Evaluating {total_metrics} RAGAS metrics",
    )
    for index, spec in enumerate(metric_specs, start=1):
        emit_progress(
            progress_callback,
            stage="evaluating_ragas",
            completed=index - 1,
            total=total_metrics,
            message=f"Evaluating metric {spec.name}",
            details={"metric": spec.name},
        )
        eligible_rows = [dict(row) for row in rows if spec.row_filter(row)]
        eligible_count = len(eligible_rows)
        if not eligible_rows:
            metrics_failed.append({"metric": spec.name, "message": "No eligible rows available for evaluation."})
            metric_row_stats[spec.name] = build_metric_row_stats(eligible_count, 0, 0)
            metric_row_diagnostics[spec.name] = []
            warnings.append(f"{spec.name} skipped: no eligible rows available.")
            emit_progress(
                progress_callback,
                stage="evaluating_ragas",
                completed=index,
                total=total_metrics,
                message=f"Skipped metric {spec.name}: no eligible rows",
                details={"metric": spec.name},
            )
            continue

        evaluation = score_metric_rows(
            evaluate=evaluate,
            dataset_factory=Dataset.from_list,
            metric_name=spec.name,
            metric=spec.metric,
            rows=eligible_rows,
            ragas_llm=ragas_llm,
            row_diagnostics_enabled=row_diagnostics_enabled,
            row_diagnostics_limit=row_diagnostics_limit,
        )

        metric_row_diagnostics[spec.name] = evaluation.diagnostics
        scored_count = apply_metric_values(per_sample_rows, spec.name, evaluation.values_by_sample_id)
        metric_row_stats[spec.name] = build_metric_row_stats(eligible_count, scored_count, evaluation.recovered_row_count)
        empty_or_nan_count = eligible_count - scored_count
        if scored_count <= 0:
            failure_message = evaluation.failure_message or "Metric produced no finite scores."
            metrics_failed.append({"metric": spec.name, "message": failure_message})
            warnings.append(f"{spec.name} produced no finite scores across {eligible_count} eligible rows.")
            emit_progress(
                progress_callback,
                stage="evaluating_ragas",
                completed=index,
                total=total_metrics,
                message=f"Metric {spec.name} produced no finite scores",
                details={"metric": spec.name},
            )
            continue
        metrics_completed.append(spec.name)
        if evaluation.recovered_row_count > 0:
            warnings.append(
                f"{spec.name} recovered {evaluation.recovered_row_count} scores via single-row re-evaluation."
            )
        if empty_or_nan_count > 0:
            warnings.append(
                f"{spec.name} returned {empty_or_nan_count} empty or non-finite scores across {eligible_count} eligible rows."
            )
        emit_progress(
            progress_callback,
            stage="evaluating_ragas",
            completed=index,
            total=total_metrics,
            message=f"Completed metric {spec.name}",
            details={"metric": spec.name},
        )

    if not metrics_completed:
        joined = "; ".join(f"{item['metric']}: {item['message']}" for item in metrics_failed) or "No metrics completed successfully."
        raise RuntimeError(f"All RAGAS metrics failed. {joined}")

    summary = build_ragas_summary(
        per_sample_rows=per_sample_rows,
        judge_model=judge_model,
        judge_base_url=judge_base_url,
        metrics_requested=metrics_requested,
        metrics_completed=metrics_completed,
        metrics_failed=metrics_failed,
        metric_row_stats=metric_row_stats,
        metric_row_diagnostics=metric_row_diagnostics,
        warnings=warnings,
    )
    return summary, per_sample_rows


@dataclass(frozen=True)
class MetricSpec:
    name: str
    metric: Any
    row_filter: Callable[[Dict[str, Any]], bool]


@dataclass(frozen=True)
class MetricEvaluationResult:
    values_by_sample_id: Dict[str, float | None]
    scored_row_count: int
    recovered_row_count: int
    diagnostics: List[Dict[str, str]]
    failure_message: str | None = None


@dataclass(frozen=True)
class MetricRowGap:
    sample_id: str
    question: str
    row: Dict[str, Any]
    initial_status: str
    initial_message: str


def build_ragas_summary(
    per_sample_rows: List[Dict[str, Any]],
    judge_model: str,
    judge_base_url: str,
    metrics_requested: List[str] | None = None,
    metrics_completed: List[str] | None = None,
    metrics_failed: List[Dict[str, str]] | None = None,
    metric_row_stats: Dict[str, Dict[str, int]] | None = None,
    metric_row_diagnostics: Dict[str, List[Dict[str, str]]] | None = None,
    warnings: List[str] | None = None,
) -> Dict[str, Any]:
    score_keys = collect_numeric_score_keys(per_sample_rows, metrics_completed or metrics_requested)
    scores: Dict[str, float] = {}
    for key in score_keys:
        values = [coerce_float(row.get(key)) for row in per_sample_rows]
        numeric_values = [value for value in values if is_finite_number(value)]
        if not numeric_values:
            continue
        scores[key] = round(sum(numeric_values) / len(numeric_values), 6)

    requested = list(metrics_requested or score_keys)
    completed = sorted(metrics_completed or scores.keys())
    failed = list(metrics_failed or [])
    stats = dict(metric_row_stats or {})
    for key in requested:
        if key in stats:
            continue
        values = [coerce_float(row.get(key)) for row in per_sample_rows]
        scored_count = sum(1 for value in values if is_finite_number(value))
        stats[key] = build_metric_row_stats(len(per_sample_rows), scored_count, 0)

    return {
        "record_count": len(per_sample_rows),
        "metrics": sorted(scores.keys()),
        "metrics_requested": requested,
        "metrics_completed": completed,
        "metrics_failed": failed,
        "metric_row_stats": stats,
        "metric_row_diagnostics": dict(metric_row_diagnostics or {}),
        "warnings": list(warnings or []),
        "scores": scores,
        "judge_provider": "openai_compatible",
        "judge_model": judge_model,
        "judge_base_url": judge_base_url,
    }


def collect_numeric_score_keys(per_sample_rows: List[Dict[str, Any]], preferred_keys: List[str] | None = None) -> List[str]:
    score_keys = set()
    if preferred_keys:
        for key in preferred_keys:
            values = [coerce_float(row.get(key)) for row in per_sample_rows]
            if any(is_finite_number(value) for value in values):
                score_keys.add(key)
        return sorted(score_keys)
    for row in per_sample_rows:
        for key, value in row.items():
            if key in RAGAS_BASE_FIELDS:
                continue
            if is_finite_number(coerce_float(value)):
                score_keys.add(key)
    return sorted(score_keys)


def coerce_float(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def is_finite_number(value: float | None) -> bool:
    return value is not None and math.isfinite(value)


def has_non_empty_contexts(contexts: Any) -> bool:
    if not isinstance(contexts, list):
        return False
    return any(bool(str(item or "").strip()) for item in contexts)


def is_context_metric_eligible_row(row: Dict[str, Any]) -> bool:
    return bool(str(row.get("ground_truth") or "").strip()) and has_non_empty_contexts(row.get("contexts"))


def build_metric_row_stats(eligible_row_count: int, scored_row_count: int, recovered_row_count: int = 0) -> Dict[str, int]:
    safe_scored_count = max(scored_row_count, 0)
    safe_recovered_count = max(recovered_row_count, 0)
    return {
        "eligible_row_count": eligible_row_count,
        "scored_row_count": safe_scored_count,
        "recovered_row_count": safe_recovered_count,
        "empty_or_nan_row_count": max(eligible_row_count - safe_scored_count, 0),
    }


def score_metric_rows(
    evaluate: Callable[..., Any],
    dataset_factory: Callable[[List[Dict[str, Any]]], Any],
    metric_name: str,
    metric: Any,
    rows: List[Dict[str, Any]],
    ragas_llm: Any,
    row_diagnostics_enabled: bool,
    row_diagnostics_limit: int,
) -> MetricEvaluationResult:
    values_by_sample_id: Dict[str, float | None] = {}
    diagnostics: List[Dict[str, str]] = []
    recovered_row_count = 0
    failure_message: str | None = None

    try:
        metric_rows = evaluate_metric_rows(
            evaluate=evaluate,
            dataset_factory=dataset_factory,
            metric_name=metric_name,
            metric=metric,
            rows=rows,
            ragas_llm=ragas_llm,
        )
        values_by_sample_id = map_metric_values(metric_rows, metric_name)
        row_gaps = collect_metric_row_gaps(rows, values_by_sample_id, phase_label="Batch")
    except Exception as exc:
        failure_message = str(exc)
        row_gaps = [
            MetricRowGap(
                sample_id=normalize_sample_id(row),
                question=str(row.get("question") or "").strip(),
                row=dict(row),
                initial_status="batch_evaluation_error",
                initial_message=str(exc),
            )
            for row in rows
        ]

    if not row_gaps:
        scored_row_count = sum(1 for row in rows if is_finite_number(values_by_sample_id.get(normalize_sample_id(row))))
        return MetricEvaluationResult(
            values_by_sample_id=values_by_sample_id,
            scored_row_count=scored_row_count,
            recovered_row_count=0,
            diagnostics=[],
            failure_message=failure_message,
        )

    if not row_diagnostics_enabled:
        diagnostics = [build_metric_row_diagnostic(gap, gap.initial_status, gap.initial_message) for gap in row_gaps]
    else:
        recovered_row_count, diagnostics = diagnose_metric_row_gaps(
            evaluate=evaluate,
            dataset_factory=dataset_factory,
            metric_name=metric_name,
            metric=metric,
            row_gaps=row_gaps,
            ragas_llm=ragas_llm,
            values_by_sample_id=values_by_sample_id,
            row_diagnostics_limit=row_diagnostics_limit,
        )

    scored_row_count = sum(1 for row in rows if is_finite_number(values_by_sample_id.get(normalize_sample_id(row))))
    if scored_row_count <= 0 and failure_message is None:
        failure_message = "Metric produced no finite scores."

    return MetricEvaluationResult(
        values_by_sample_id=values_by_sample_id,
        scored_row_count=scored_row_count,
        recovered_row_count=recovered_row_count,
        diagnostics=diagnostics,
        failure_message=failure_message,
    )


def collect_metric_row_gaps(
    rows: List[Dict[str, Any]],
    values_by_sample_id: Dict[str, float | None],
    phase_label: str,
) -> List[MetricRowGap]:
    gaps: List[MetricRowGap] = []
    for row in rows:
        sample_id = normalize_sample_id(row)
        status, message = classify_metric_value(sample_id, values_by_sample_id, phase_label=phase_label)
        if status is None or message is None:
            continue
        gaps.append(
            MetricRowGap(
                sample_id=sample_id,
                question=str(row.get("question") or "").strip(),
                row=dict(row),
                initial_status=status,
                initial_message=message,
            )
        )
    return gaps


def diagnose_metric_row_gaps(
    evaluate: Callable[..., Any],
    dataset_factory: Callable[[List[Dict[str, Any]]], Any],
    metric_name: str,
    metric: Any,
    row_gaps: List[MetricRowGap],
    ragas_llm: Any,
    values_by_sample_id: Dict[str, float | None],
    row_diagnostics_limit: int,
) -> Tuple[int, List[Dict[str, str]]]:
    recovered_row_count = 0
    diagnostics: List[Dict[str, str]] = []

    for index, gap in enumerate(row_gaps):
        if row_diagnostics_limit >= 0 and index >= row_diagnostics_limit:
            diagnostics.append(
                build_metric_row_diagnostic(
                    gap,
                    status="diagnostic_skipped",
                    message=f"Single-row re-evaluation skipped because limit {row_diagnostics_limit} was reached.",
                )
            )
            continue

        try:
            metric_rows = evaluate_metric_rows(
                evaluate=evaluate,
                dataset_factory=dataset_factory,
                metric_name=metric_name,
                metric=metric,
                rows=[dict(gap.row)],
                ragas_llm=ragas_llm,
            )
            single_value_by_sample_id = map_metric_values(metric_rows, metric_name)
            if gap.sample_id in single_value_by_sample_id:
                values_by_sample_id[gap.sample_id] = single_value_by_sample_id[gap.sample_id]

            status, message = classify_metric_value(
                gap.sample_id,
                single_value_by_sample_id,
                phase_label="Single-row",
            )
            if status is None:
                recovered_row_count += 1
                continue
            diagnostics.append(build_metric_row_diagnostic(gap, status=status, message=message))
        except Exception as exc:
            diagnostics.append(
                build_metric_row_diagnostic(
                    gap,
                    status="row_evaluation_error",
                    message=str(exc),
                )
            )

    return recovered_row_count, diagnostics


def build_metric_row_diagnostic(gap: MetricRowGap, status: str, message: str | None) -> Dict[str, str]:
    return {
        "sample_id": gap.sample_id,
        "question": gap.question,
        "initial_status": gap.initial_status,
        "initial_message": gap.initial_message,
        "status": status,
        "message": str(message or "").strip(),
    }


def classify_metric_value(
    sample_id: str,
    values_by_sample_id: Dict[str, float | None],
    phase_label: str,
) -> Tuple[str | None, str | None]:
    if sample_id not in values_by_sample_id:
        return "missing_metric_value", f"{phase_label} evaluation returned no metric value for this sample."

    value = values_by_sample_id.get(sample_id)
    if value is None:
        return "empty_metric_value", f"{phase_label} evaluation returned an empty metric value."
    if not is_finite_number(value):
        return "non_finite_metric_value", f"{phase_label} evaluation returned a non-finite metric value: {value}."
    return None, None


def evaluate_metric_rows(
    evaluate: Callable[..., Any],
    dataset_factory: Callable[[List[Dict[str, Any]]], Any],
    metric_name: str,
    metric: Any,
    rows: List[Dict[str, Any]],
    ragas_llm: Any,
) -> List[Dict[str, Any]]:
    dataset = dataset_factory(rows)
    result = evaluate(dataset, metrics=[metric], llm=ragas_llm)
    dataframe = result.to_pandas()
    metric_rows = dataframe.to_dict(orient="records")
    if not metric_rows:
        raise RuntimeError(f"{metric_name} returned no rows.")
    return metric_rows


def map_metric_values(metric_rows: List[Dict[str, Any]], metric_name: str) -> Dict[str, float | None]:
    out: Dict[str, float | None] = {}
    for row in metric_rows:
        sample_id = str(row.get("sample_id") or "").strip()
        if not sample_id:
            continue
        out[sample_id] = coerce_float(extract_metric_value(row, metric_name))
    return out


def extract_metric_value(row: Dict[str, Any], metric_name: str) -> Any:
    if metric_name in row:
        return row.get(metric_name)
    candidate_keys = [key for key in row.keys() if key not in RAGAS_BASE_FIELDS]
    if len(candidate_keys) == 1:
        return row.get(candidate_keys[0])
    return None


def apply_metric_values(
    per_sample_rows: List[Dict[str, Any]],
    metric_name: str,
    values_by_sample_id: Dict[str, float | None],
) -> int:
    scored_count = 0
    for row in per_sample_rows:
        sample_id = str(row.get("sample_id") or "").strip()
        if not sample_id or sample_id not in values_by_sample_id:
            continue
        value = values_by_sample_id[sample_id]
        row[metric_name] = value
        if is_finite_number(value):
            scored_count += 1
    return scored_count


def normalize_sample_id(row: Dict[str, Any]) -> str:
    return str(row.get("sample_id") or "").strip()


def sanitize_per_sample_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [sanitize_per_sample_row(row) for row in rows]


def sanitize_per_sample_row(row: Dict[str, Any]) -> Dict[str, Any]:
    sanitized: Dict[str, Any] = {}
    for key, value in row.items():
        if isinstance(value, dict):
            sanitized[key] = sanitize_per_sample_row(value)
            continue
        if isinstance(value, list):
            sanitized[key] = [sanitize_per_sample_row(item) if isinstance(item, dict) else sanitize_csv_scalar(item) for item in value]
            continue
        sanitized[key] = sanitize_csv_scalar(value)
    return sanitized


def sanitize_csv_scalar(value: Any) -> Any:
    numeric = coerce_float(value)
    if numeric is not None and not is_finite_number(numeric):
        return None
    return value


def read_bool_env(name: str, default: bool) -> bool:
    text = str(os.getenv(name) or "").strip().lower()
    if not text:
        return default
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return default


def read_first_non_empty_env(*names: str, default: str = "") -> str:
    for name in names:
        value = str(os.getenv(name) or "").strip()
        if value:
            return value
    return str(default).strip()


def read_positive_int_env(name: str, default: int) -> int:
    value = read_non_negative_int_env(name, default)
    return value if value > 0 else default


def read_non_negative_int_env(name: str, default: int) -> int:
    text = str(os.getenv(name) or "").strip()
    if not text:
        return default
    try:
        value = int(text)
    except ValueError:
        return default
    return value if value >= 0 else default


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_csv_rows(path: Path, rows: List[Dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames = collect_fieldnames(rows)
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: normalize_csv_value(row.get(field)) for field in fieldnames})


def collect_fieldnames(rows: List[Dict[str, Any]]) -> List[str]:
    fieldnames: List[str] = []
    seen = set()
    for row in rows:
        for key in row.keys():
            if key in seen:
                continue
            seen.add(key)
            fieldnames.append(key)
    return fieldnames


def normalize_csv_value(value: Any) -> str:
    if isinstance(value, list):
        return json.dumps(value, ensure_ascii=False)
    if value is None:
        return ""
    return str(value)
