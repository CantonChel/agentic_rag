"""RAGAS integration for the stage-6 benchmark runner."""

from __future__ import annotations

import asyncio
import csv
import inspect
import json
import math
import os
import queue
import re
import threading
import time
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
DEFAULT_METRIC_TIMEOUT_SECONDS = 180
DEFAULT_ROW_TIMEOUT_SECONDS = 60
DEFAULT_METRIC_MAX_CONCURRENCY = 2
DEFAULT_RAGAS_MAX_CONTEXT_COUNT = 6
DEFAULT_RAGAS_MAX_CONTEXT_CHARS = 1200
DEFAULT_RAGAS_MAX_ANSWER_CHARS = 1200
DEFAULT_RAGAS_MAX_GROUND_TRUTH_CHARS = 0
DEFAULT_RAGAS_NORMALIZE_MARKDOWN = True
DEFAULT_RAGAS_NORMALIZE_CJK_SENTENCE_PUNCTUATION = True
DEFAULT_RAGAS_FAITHFULNESS_VARIANT = "default"
DEFAULT_RAGAS_HHEM_DEVICE = "cpu"
DEFAULT_RAGAS_HHEM_BATCH_SIZE = 10
DEFAULT_RAGAS_INSTRUCTOR_MODE = "auto"
DEFAULT_JUDGE_BASE_URL = "https://api.deepseek.com/v1"
DEFAULT_JUDGE_MODEL = "deepseek-chat"


MARKDOWN_HEADING_PATTERN = re.compile(r"^\s{0,3}#{1,6}\s*")
MARKDOWN_LIST_PATTERN = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)")
MARKDOWN_BLOCKQUOTE_PATTERN = re.compile(r"^\s*>\s*")
MARKDOWN_TABLE_SEPARATOR_PATTERN = re.compile(r"^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*$")
MARKDOWN_FENCE_PATTERN = re.compile(r"^\s*```")
MULTISPACE_PATTERN = re.compile(r"[ \t]{2,}")
MULTINEWLINE_PATTERN = re.compile(r"\n{3,}")


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
    remove_file_if_exists(summary_path)
    remove_file_if_exists(per_sample_csv_path)
    remove_file_if_exists(error_path)

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
        import ragas
        from openai import AsyncOpenAI
        from ragas import evaluate
        from ragas.llms import llm_factory
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
    metric_timeout_seconds = read_non_negative_int_env(
        "RAGAS_METRIC_TIMEOUT_SECONDS",
        DEFAULT_METRIC_TIMEOUT_SECONDS,
    )
    row_timeout_seconds = read_non_negative_int_env(
        "RAGAS_ROW_TIMEOUT_SECONDS",
        DEFAULT_ROW_TIMEOUT_SECONDS,
    )
    max_concurrency = read_positive_int_env(
        "RAGAS_METRIC_MAX_CONCURRENCY",
        DEFAULT_METRIC_MAX_CONCURRENCY,
    )
    prepared_rows, input_preparation = prepare_ragas_rows(rows)
    if not judge_api_key:
        raise RuntimeError(
            "RAGAS judge API key is empty. Set RAGAS_JUDGE_API_KEY, DEEPSEEK_API_KEY, or MINIMAX_API_KEY."
        )

    judge_client = AsyncOpenAI(
        api_key=judge_api_key,
        base_url=judge_base_url,
        timeout=judge_timeout_seconds,
        max_retries=judge_max_retries,
    )
    ragas_llm = build_ragas_llm(
        llm_factory=llm_factory,
        judge_client=judge_client,
        judge_model=judge_model,
        judge_base_url=judge_base_url,
    )

    metric_specs, metric_warnings = build_metric_specs(prepared_rows, ragas_llm)
    warnings: List[str] = []
    warnings.extend(build_ragas_input_preparation_warnings(input_preparation))
    warnings.extend(metric_warnings)
    warnings.append(f"ragas runtime version: {getattr(ragas, '__version__', 'unknown')}")

    per_sample_rows = [dict(row) for row in prepared_rows]
    metrics_requested = [spec.name for spec in metric_specs]
    metrics_completed: List[str] = []
    metrics_failed: List[Dict[str, str]] = []
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
        eligible_rows = [dict(row) for row in prepared_rows if spec.row_filter(row)]
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
            dataset_factory=lambda items: items,
            metric_name=spec.name,
            metric=spec.metric,
            rows=eligible_rows,
            ragas_llm=ragas_llm,
            row_diagnostics_enabled=row_diagnostics_enabled,
            row_diagnostics_limit=row_diagnostics_limit,
            timeout_seconds=metric_timeout_seconds,
            row_timeout_seconds=row_timeout_seconds,
            max_concurrency=max_concurrency,
            judge_timeout_seconds=judge_timeout_seconds,
            judge_max_retries=judge_max_retries,
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
        input_preparation=input_preparation,
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


def build_ragas_llm(
    llm_factory: Callable[..., Any],
    judge_client: Any,
    judge_model: str,
    judge_base_url: str,
) -> Any:
    mode = select_ragas_instructor_mode(judge_base_url)
    kwargs: Dict[str, Any] = {
        "provider": "openai",
        "client": judge_client,
        "temperature": 0,
    }
    if mode is not None:
        kwargs["mode"] = mode
    return llm_factory(judge_model, **kwargs)


def select_ragas_instructor_mode(judge_base_url: str) -> Any:
    import instructor

    configured = read_first_non_empty_env(
        "RAGAS_INSTRUCTOR_MODE",
        default=DEFAULT_RAGAS_INSTRUCTOR_MODE,
    ).strip().lower()
    if configured in {"", "auto"}:
        return None

    mode_mapping = {
        "json": instructor.Mode.JSON,
        "md_json": instructor.Mode.MD_JSON,
        "json_schema": instructor.Mode.JSON_SCHEMA,
        "tools": instructor.Mode.TOOLS,
    }
    return mode_mapping.get(configured, instructor.Mode.MD_JSON)


def build_metric_specs(rows: List[Dict[str, Any]], ragas_llm: Any) -> Tuple[List[MetricSpec], List[str]]:
    from ragas.metrics.collections import ContextPrecision
    from ragas.metrics.collections import ContextRecall

    warnings: List[str] = []
    metric_specs: List[MetricSpec] = []
    if any(bool(str(row.get("ground_truth") or "").strip()) for row in rows):
        metric_specs.extend(
            [
                MetricSpec(
                    name="context_precision",
                    metric=ContextPrecision(llm=ragas_llm),
                    row_filter=is_context_metric_eligible_row,
                ),
                MetricSpec(
                    name="context_recall",
                    metric=ContextRecall(llm=ragas_llm),
                    row_filter=is_context_metric_eligible_row,
                ),
            ]
        )

    faithfulness_metric, faithfulness_warnings = build_faithfulness_metric(ragas_llm)
    warnings.extend(faithfulness_warnings)
    metric_specs.append(MetricSpec(name="faithfulness", metric=faithfulness_metric, row_filter=lambda row: True))
    return metric_specs, warnings


def build_faithfulness_metric(ragas_llm: Any) -> Tuple[Any, List[str]]:
    from ragas.metrics.collections import Faithfulness

    variant = read_first_non_empty_env(
        "RAGAS_FAITHFULNESS_VARIANT",
        default=DEFAULT_RAGAS_FAITHFULNESS_VARIANT,
    ).strip().lower()
    if variant in {"", "default", "llm"}:
        return Faithfulness(llm=ragas_llm), []

    if variant == "hhem":
        device = read_first_non_empty_env("RAGAS_HHEM_DEVICE", default=DEFAULT_RAGAS_HHEM_DEVICE)
        batch_size = read_positive_int_env("RAGAS_HHEM_BATCH_SIZE", DEFAULT_RAGAS_HHEM_BATCH_SIZE)
        try:
            from ragas.metrics import FaithfulnesswithHHEM

            return FaithfulnesswithHHEM(llm=ragas_llm, device=device, batch_size=batch_size), [
                f"faithfulness metric variant: hhem (device={device}, batch_size={batch_size})"
            ]
        except Exception as exc:
            return Faithfulness(llm=ragas_llm), [
                f"faithfulness metric variant hhem unavailable, fell back to default ragas faithfulness: {exc}"
            ]

    return Faithfulness(llm=ragas_llm), [
        f"Unsupported RAGAS_FAITHFULNESS_VARIANT={variant!r}; fell back to default ragas faithfulness."
    ]


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
    input_preparation: Dict[str, Any] | None = None,
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
        "input_preparation": dict(input_preparation or {}),
    }


def prepare_ragas_rows(rows: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    config = read_ragas_input_config()
    prepared_rows: List[Dict[str, Any]] = []
    stats = {
        "rows_with_modified_fields": 0,
        "rows_with_modified_answers": 0,
        "rows_with_truncated_answers": 0,
        "rows_with_modified_ground_truths": 0,
        "rows_with_truncated_ground_truths": 0,
        "rows_with_modified_contexts": 0,
        "rows_with_truncated_contexts": 0,
        "rows_with_context_count_capped": 0,
    }

    for row in rows:
        prepared_row, row_stats = prepare_ragas_row(row, config)
        prepared_rows.append(prepared_row)
        for key, value in row_stats.items():
            if value:
                stats[key] += 1

    return prepared_rows, {
        "row_count": len(prepared_rows),
        "normalize_markdown": config["normalize_markdown"],
        "normalize_cjk_sentence_punctuation": config["normalize_cjk_sentence_punctuation"],
        "max_context_count": config["max_context_count"],
        "max_context_chars": config["max_context_chars"],
        "max_answer_chars": config["max_answer_chars"],
        "max_ground_truth_chars": config["max_ground_truth_chars"],
        **stats,
    }


def read_ragas_input_config() -> Dict[str, Any]:
    return {
        "normalize_markdown": read_bool_env("RAGAS_NORMALIZE_MARKDOWN", DEFAULT_RAGAS_NORMALIZE_MARKDOWN),
        "normalize_cjk_sentence_punctuation": read_bool_env(
            "RAGAS_NORMALIZE_CJK_SENTENCE_PUNCTUATION",
            DEFAULT_RAGAS_NORMALIZE_CJK_SENTENCE_PUNCTUATION,
        ),
        "max_context_count": read_non_negative_int_env("RAGAS_MAX_CONTEXT_COUNT", DEFAULT_RAGAS_MAX_CONTEXT_COUNT),
        "max_context_chars": read_non_negative_int_env("RAGAS_MAX_CONTEXT_CHARS", DEFAULT_RAGAS_MAX_CONTEXT_CHARS),
        "max_answer_chars": read_non_negative_int_env("RAGAS_MAX_ANSWER_CHARS", DEFAULT_RAGAS_MAX_ANSWER_CHARS),
        "max_ground_truth_chars": read_non_negative_int_env(
            "RAGAS_MAX_GROUND_TRUTH_CHARS",
            DEFAULT_RAGAS_MAX_GROUND_TRUTH_CHARS,
        ),
    }


def prepare_ragas_row(row: Dict[str, Any], config: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, bool]]:
    prepared_row = dict(row)
    original_answer = str(row.get("answer") or "")
    prepared_answer = normalize_ragas_text(
        original_answer,
        normalize_markdown=config["normalize_markdown"],
        normalize_cjk_sentence_punctuation=config["normalize_cjk_sentence_punctuation"],
    )
    prepared_answer, answer_truncated = truncate_ragas_text(prepared_answer, config["max_answer_chars"])
    prepared_row["answer"] = prepared_answer

    original_ground_truth = str(row.get("ground_truth") or "")
    prepared_ground_truth = normalize_ragas_text(
        original_ground_truth,
        normalize_markdown=config["normalize_markdown"],
        normalize_cjk_sentence_punctuation=config["normalize_cjk_sentence_punctuation"],
    )
    prepared_ground_truth, ground_truth_truncated = truncate_ragas_text(
        prepared_ground_truth,
        config["max_ground_truth_chars"],
    )
    prepared_row["ground_truth"] = prepared_ground_truth

    original_contexts = [str(item or "") for item in (row.get("contexts") or []) if str(item or "").strip()]
    prepared_contexts: List[str] = []
    context_truncated = False
    for context in original_contexts:
        normalized_context = normalize_ragas_text(
            context,
            normalize_markdown=config["normalize_markdown"],
            normalize_cjk_sentence_punctuation=False,
        )
        normalized_context, truncated = truncate_ragas_text(normalized_context, config["max_context_chars"])
        context_truncated = context_truncated or truncated
        if normalized_context.strip():
            prepared_contexts.append(normalized_context)

    context_count_capped = False
    max_context_count = config["max_context_count"]
    if max_context_count > 0 and len(prepared_contexts) > max_context_count:
        prepared_contexts = prepared_contexts[:max_context_count]
        context_count_capped = True

    prepared_row["contexts"] = prepared_contexts or [""]
    prepared_row["question"] = normalize_ragas_whitespace(str(row.get("question") or ""))

    normalized_original_contexts = [normalize_ragas_whitespace(item) for item in original_contexts]
    row_stats = {
        "rows_with_modified_fields": (
            normalize_ragas_whitespace(original_answer) != prepared_answer
            or normalize_ragas_whitespace(original_ground_truth) != prepared_ground_truth
            or normalized_original_contexts != prepared_contexts
        ),
        "rows_with_modified_answers": normalize_ragas_whitespace(original_answer) != prepared_answer,
        "rows_with_truncated_answers": answer_truncated,
        "rows_with_modified_ground_truths": normalize_ragas_whitespace(original_ground_truth) != prepared_ground_truth,
        "rows_with_truncated_ground_truths": ground_truth_truncated,
        "rows_with_modified_contexts": normalized_original_contexts != prepared_contexts,
        "rows_with_truncated_contexts": context_truncated,
        "rows_with_context_count_capped": context_count_capped,
    }
    return prepared_row, row_stats


def build_ragas_input_preparation_warnings(input_preparation: Dict[str, Any]) -> List[str]:
    warnings: List[str] = []
    modified_row_count = int(input_preparation.get("rows_with_modified_fields") or 0)
    if modified_row_count > 0:
        warnings.append(
            "RAGAS input normalization modified "
            f"{modified_row_count}/{int(input_preparation.get('row_count') or 0)} rows "
            "(markdown flattened / whitespace normalized / Chinese sentence punctuation normalized)."
        )

    context_cap_count = int(input_preparation.get("rows_with_context_count_capped") or 0)
    if context_cap_count > 0:
        warnings.append(
            f"RAGAS input capped context count for {context_cap_count} rows at "
            f"{int(input_preparation.get('max_context_count') or 0)} contexts."
        )

    answer_truncation_count = int(input_preparation.get("rows_with_truncated_answers") or 0)
    if answer_truncation_count > 0:
        warnings.append(
            f"RAGAS input truncated answers for {answer_truncation_count} rows at "
            f"{int(input_preparation.get('max_answer_chars') or 0)} characters."
        )

    context_truncation_count = int(input_preparation.get("rows_with_truncated_contexts") or 0)
    if context_truncation_count > 0:
        warnings.append(
            f"RAGAS input truncated contexts for {context_truncation_count} rows at "
            f"{int(input_preparation.get('max_context_chars') or 0)} characters."
        )
    return warnings


def normalize_ragas_text(
    value: str,
    *,
    normalize_markdown: bool,
    normalize_cjk_sentence_punctuation: bool,
) -> str:
    text = str(value or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    if not text:
        return ""

    if normalize_markdown:
        lines: List[str] = []
        for raw_line in text.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if MARKDOWN_TABLE_SEPARATOR_PATTERN.match(line):
                continue
            if MARKDOWN_FENCE_PATTERN.match(line):
                continue
            line = MARKDOWN_HEADING_PATTERN.sub("", line)
            line = MARKDOWN_LIST_PATTERN.sub("", line)
            line = MARKDOWN_BLOCKQUOTE_PATTERN.sub("", line)
            line = line.replace("`", "")
            line = line.replace("|", " ; ")
            line = line.replace("---", " ")
            line = normalize_ragas_whitespace(line)
            line = line.strip(" ;")
            if line:
                lines.append(line)
        text = "\n".join(lines)

    if normalize_cjk_sentence_punctuation:
        text = (
            text.replace("。", ". ")
            .replace("！", ". ")
            .replace("？", ". ")
            .replace("；", ". ")
        )

    text = normalize_ragas_whitespace(text)
    text = MULTINEWLINE_PATTERN.sub("\n\n", text)
    return text.strip()


def normalize_ragas_whitespace(value: str) -> str:
    text = str(value or "").replace("\r\n", "\n").replace("\r", "\n")
    collapsed_lines = [MULTISPACE_PATTERN.sub(" ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in collapsed_lines if line).strip()


def truncate_ragas_text(value: str, max_chars: int) -> Tuple[str, bool]:
    text = str(value or "").strip()
    if max_chars <= 0 or len(text) <= max_chars:
        return text, False
    truncated = text[:max_chars].rstrip()
    if not truncated:
        return "", True
    return f"{truncated} ...", True


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
    if hasattr(value, "value") and not isinstance(value, (int, float, str)):
        return coerce_float(getattr(value, "value"))
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
    timeout_seconds: int,
    row_timeout_seconds: int | None = None,
    max_concurrency: int | None = None,
    judge_timeout_seconds: int | None = None,
    judge_max_retries: int | None = None,
) -> MetricEvaluationResult:
    if should_use_direct_metric_scoring(evaluate):
        return score_metric_rows_direct(
            metric_name=metric_name,
            metric=metric,
            rows=rows,
            ragas_llm=ragas_llm,
            row_diagnostics_enabled=row_diagnostics_enabled,
            row_diagnostics_limit=row_diagnostics_limit,
            timeout_seconds=timeout_seconds,
            row_timeout_seconds=row_timeout_seconds,
            max_concurrency=max_concurrency,
            judge_timeout_seconds=judge_timeout_seconds,
            judge_max_retries=judge_max_retries,
        )

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
            timeout_seconds=timeout_seconds,
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

    if failure_message is not None or not row_diagnostics_enabled:
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
            timeout_seconds=timeout_seconds,
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


def should_use_direct_metric_scoring(evaluate: Callable[..., Any]) -> bool:
    module_name = str(getattr(evaluate, "__module__", "") or "")
    qualname = str(getattr(evaluate, "__qualname__", "") or getattr(evaluate, "__name__", "") or "")
    return module_name.startswith("ragas.") and "evaluate" in qualname


@dataclass(frozen=True)
class DirectMetricRowResult:
    sample_id: str
    question: str
    value: float | None
    error_message: str | None = None


def score_metric_rows_direct(
    metric_name: str,
    metric: Any,
    rows: List[Dict[str, Any]],
    ragas_llm: Any,
    row_diagnostics_enabled: bool,
    row_diagnostics_limit: int,
    timeout_seconds: int,
    row_timeout_seconds: int | None,
    max_concurrency: int | None,
    judge_timeout_seconds: int | None,
    judge_max_retries: int | None,
) -> MetricEvaluationResult:
    effective_row_timeout = row_timeout_seconds if row_timeout_seconds and row_timeout_seconds > 0 else timeout_seconds
    effective_concurrency = max(max_concurrency or DEFAULT_METRIC_MAX_CONCURRENCY, 1)
    prepare_metric_for_direct_scoring(
        metric=metric,
        ragas_llm=ragas_llm,
        timeout_seconds=judge_timeout_seconds or effective_row_timeout,
        max_concurrency=effective_concurrency,
        max_retries=judge_max_retries or DEFAULT_JUDGE_MAX_RETRIES,
    )
    row_results = run_direct_metric_row_scores(
        metric_name=metric_name,
        metric=metric,
        rows=rows,
        row_timeout_seconds=effective_row_timeout,
        max_concurrency=effective_concurrency,
        timeout_seconds=timeout_seconds,
    )

    values_by_sample_id: Dict[str, float | None] = {
        item.sample_id: item.value
        for item in row_results
        if item.sample_id
    }
    recovered_row_count = 0
    diagnostics: List[Dict[str, str]] = []
    row_results_by_sample_id = {item.sample_id: item for item in row_results if item.sample_id}
    row_gaps = collect_metric_row_gaps(rows, values_by_sample_id, phase_label="Batch")

    if not row_gaps:
        scored_row_count = sum(1 for row in rows if is_finite_number(values_by_sample_id.get(normalize_sample_id(row))))
        return MetricEvaluationResult(
            values_by_sample_id=values_by_sample_id,
            scored_row_count=scored_row_count,
            recovered_row_count=0,
            diagnostics=[],
            failure_message=None,
        )

    if not row_diagnostics_enabled:
        diagnostics = build_direct_metric_row_diagnostics(row_gaps, row_results_by_sample_id)
    else:
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
                rerun = run_direct_metric_row_score(
                    metric_name=metric_name,
                    metric=metric,
                    row=gap.row,
                    row_timeout_seconds=effective_row_timeout,
                    timeout_seconds=min(effective_row_timeout, timeout_seconds),
                )
            except Exception as exc:
                rerun = DirectMetricRowResult(
                    sample_id=gap.sample_id,
                    question=gap.question,
                    value=None,
                    error_message=str(exc),
                )
            row_results_by_sample_id[gap.sample_id] = rerun
            if is_finite_number(rerun.value):
                values_by_sample_id[gap.sample_id] = rerun.value
                recovered_row_count += 1
                continue
            diagnostics.append(
                build_direct_metric_row_diagnostic(
                    gap=gap,
                    initial_result=row_results_by_sample_id.get(gap.sample_id),
                    rerun_result=rerun,
                )
            )

    scored_row_count = sum(1 for row in rows if is_finite_number(values_by_sample_id.get(normalize_sample_id(row))))
    failure_message = None
    if scored_row_count <= 0:
        failure_messages = sorted(
            {
                (result.error_message or "").strip()
                for result in row_results_by_sample_id.values()
                if str(result.error_message or "").strip()
            }
        )
        if failure_messages:
            failure_message = "; ".join(failure_messages)
        else:
            failure_message = "Metric produced no finite scores."

    return MetricEvaluationResult(
        values_by_sample_id=values_by_sample_id,
        scored_row_count=scored_row_count,
        recovered_row_count=recovered_row_count,
        diagnostics=diagnostics,
        failure_message=failure_message,
    )


def build_direct_metric_row_diagnostics(
    row_gaps: List[MetricRowGap],
    row_results_by_sample_id: Dict[str, "DirectMetricRowResult"],
) -> List[Dict[str, str]]:
    diagnostics: List[Dict[str, str]] = []
    for gap in row_gaps:
        diagnostics.append(
            build_direct_metric_row_diagnostic(
                gap=gap,
                initial_result=row_results_by_sample_id.get(gap.sample_id),
                rerun_result=None,
            )
        )
    return diagnostics


def build_direct_metric_row_diagnostic(
    gap: MetricRowGap,
    initial_result: "DirectMetricRowResult | None",
    rerun_result: "DirectMetricRowResult | None",
) -> Dict[str, str]:
    if rerun_result is not None and str(rerun_result.error_message or "").strip():
        return build_metric_row_diagnostic(
            gap,
            status="row_evaluation_error",
            message=rerun_result.error_message,
        )
    if initial_result is not None and str(initial_result.error_message or "").strip():
        return build_metric_row_diagnostic(
            gap,
            status="row_evaluation_error",
            message=initial_result.error_message,
        )
    return build_metric_row_diagnostic(gap, status=gap.initial_status, message=gap.initial_message)


def prepare_metric_for_direct_scoring(
    metric: Any,
    ragas_llm: Any,
    timeout_seconds: int,
    max_concurrency: int,
    max_retries: int,
) -> None:
    try:
        from ragas.run_config import RunConfig
    except ImportError:
        return

    if hasattr(metric, "llm"):
        metric.llm = ragas_llm

    init = getattr(metric, "init", None)
    if not callable(init):
        return

    init(
        RunConfig(
            timeout=max(timeout_seconds, 1),
            max_retries=max(max_retries, 1),
            max_wait=min(max(timeout_seconds, 1), 10),
            max_workers=max(max_concurrency, 1),
        )
    )


def run_direct_metric_row_scores(
    metric_name: str,
    metric: Any,
    rows: List[Dict[str, Any]],
    row_timeout_seconds: int,
    max_concurrency: int,
    timeout_seconds: int,
) -> List["DirectMetricRowResult"]:
    deadline = time.monotonic() + timeout_seconds if timeout_seconds > 0 else None
    results: List["DirectMetricRowResult"] = []
    for row in rows:
        remaining_seconds = row_timeout_seconds
        if deadline is not None:
            remaining_seconds = min(row_timeout_seconds, max(int(deadline - time.monotonic()), 0))
        sample_id = normalize_sample_id(row)
        question = str(row.get("question") or "").strip()
        if remaining_seconds <= 0:
            results.append(
                DirectMetricRowResult(
                    sample_id=sample_id,
                    question=question,
                    value=None,
                    error_message=f"RAGAS metric {metric_name} timed out after {timeout_seconds} seconds.",
                )
            )
            continue

        try:
            result = run_direct_metric_row_score(
                metric_name=metric_name,
                metric=metric,
                row=dict(row),
                row_timeout_seconds=remaining_seconds,
                timeout_seconds=remaining_seconds,
            )
        except Exception as exc:
            result = DirectMetricRowResult(
                sample_id=sample_id,
                question=question,
                value=None,
                error_message=str(exc),
            )
        results.append(result)
    return results


def run_direct_metric_row_score(
    metric_name: str,
    metric: Any,
    row: Dict[str, Any],
    row_timeout_seconds: int,
    timeout_seconds: int,
) -> "DirectMetricRowResult":
    def run_once() -> "DirectMetricRowResult":
        loop = asyncio.get_event_loop()
        return loop.run_until_complete(
            score_direct_metric_row_async(
                metric_name=metric_name,
                metric=metric,
                row=dict(row),
                row_timeout_seconds=row_timeout_seconds,
            )
        )

    return run_with_isolated_event_loop(
        operation_name=f"RAGAS row {metric_name}",
        fn=run_once,
        timeout_seconds=timeout_seconds,
    )


async def score_direct_metric_row_async(
    metric_name: str,
    metric: Any,
    row: Dict[str, Any],
    row_timeout_seconds: int,
) -> "DirectMetricRowResult":
    sample_id = normalize_sample_id(row)
    question = str(row.get("question") or "").strip()
    try:
        score = await asyncio.wait_for(
            call_metric_ascore(
                metric_name=metric_name,
                metric=metric,
                row=dict(row),
                row_timeout_seconds=row_timeout_seconds,
            ),
            timeout=row_timeout_seconds if row_timeout_seconds > 0 else None,
        )
        return DirectMetricRowResult(
            sample_id=sample_id,
            question=question,
            value=coerce_float(score),
            error_message=None,
        )
    except Exception as exc:
        return DirectMetricRowResult(
            sample_id=sample_id,
            question=question,
            value=None,
            error_message=str(exc),
        )


async def call_metric_ascore(
    metric_name: str,
    metric: Any,
    row: Dict[str, Any],
    row_timeout_seconds: int,
) -> Any:
    ascore = getattr(metric, "ascore")
    signature = inspect.signature(ascore)
    parameter_names = list(signature.parameters.keys())

    if any(name in parameter_names for name in {"user_input", "response", "retrieved_contexts", "reference"}):
        kwargs: Dict[str, Any] = {}
        if "user_input" in parameter_names:
            kwargs["user_input"] = str(row.get("question") or "")
        if "response" in parameter_names:
            kwargs["response"] = str(row.get("answer") or "")
        if "retrieved_contexts" in parameter_names:
            kwargs["retrieved_contexts"] = list(row.get("contexts") or [])
        if "reference" in parameter_names:
            kwargs["reference"] = str(row.get("ground_truth") or "")
        if "timeout" in parameter_names:
            kwargs["timeout"] = row_timeout_seconds if row_timeout_seconds > 0 else None
        if "callbacks" in parameter_names:
            kwargs["callbacks"] = []
        return await ascore(**kwargs)

    if "row" in parameter_names:
        kwargs = {"row": dict(row)}
        if "callbacks" in parameter_names:
            kwargs["callbacks"] = []
        if "timeout" in parameter_names:
            kwargs["timeout"] = row_timeout_seconds if row_timeout_seconds > 0 else None
        return await ascore(**kwargs)

    kwargs = {}
    if "callbacks" in parameter_names:
        kwargs["callbacks"] = []
    if "timeout" in parameter_names:
        kwargs["timeout"] = row_timeout_seconds if row_timeout_seconds > 0 else None

    expects_positional_row = bool(parameter_names) and parameter_names[0] not in {"callbacks", "timeout"}
    if expects_positional_row:
        return await ascore(dict(row), **kwargs)
    return await ascore(**kwargs)


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
    timeout_seconds: int,
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
                timeout_seconds=timeout_seconds,
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
    timeout_seconds: int,
) -> List[Dict[str, Any]]:
    def run_once() -> List[Dict[str, Any]]:
        dataset = dataset_factory(rows)
        result = evaluate(dataset, metrics=[metric], llm=ragas_llm)
        dataframe = result.to_pandas()
        metric_rows = dataframe.to_dict(orient="records")
        if not metric_rows:
            raise RuntimeError(f"{metric_name} returned no rows.")
        return metric_rows

    return run_with_isolated_event_loop(
        operation_name=f"RAGAS metric {metric_name}",
        fn=run_once,
        timeout_seconds=timeout_seconds,
    )


def run_with_isolated_event_loop(
    operation_name: str,
    fn: Callable[[], Any],
    timeout_seconds: int,
) -> Any:
    result_queue: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=1)

    def worker() -> None:
        loop: asyncio.AbstractEventLoop | None = None
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            result_queue.put(("ok", fn()))
        except BaseException as exc:  # pragma: no cover - exercised via callers
            result_queue.put(("err", exc))
        finally:
            if loop is not None and not loop.is_closed():
                loop.close()
            asyncio.set_event_loop(None)

    thread = threading.Thread(target=worker, name=f"ragas-{operation_name}", daemon=True)
    thread.start()
    thread.join(timeout_seconds if timeout_seconds > 0 else None)
    if thread.is_alive():
        raise TimeoutError(f"{operation_name} timed out after {timeout_seconds} seconds.")

    try:
        status, payload = result_queue.get_nowait()
    except queue.Empty as exc:  # pragma: no cover - defensive
        raise RuntimeError(f"{operation_name} finished without returning a result.") from exc

    if status == "err":
        raise payload
    return payload


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


def remove_file_if_exists(path: Path) -> None:
    if path.exists():
        path.unlink()


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
