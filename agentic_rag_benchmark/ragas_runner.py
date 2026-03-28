"""RAGAS integration for the stage-6 benchmark runner."""

from __future__ import annotations

import csv
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from typing import Callable
from typing import Dict
from typing import List
from typing import Tuple

from .runner import RunBenchmarkReport
from .runner import RunBenchmarkSampleResult


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
) -> RagasArtifacts:
    """Evaluate collected benchmark results with RAGAS."""

    rows = build_ragas_rows(report.sample_results)
    summary_path = output_dir / "ragas_summary.json"
    per_sample_csv_path = output_dir / "ragas_scores_per_sample.csv"
    error_path = output_dir / "ragas_error.json"
    evaluator = evaluator or run_ragas_evaluation

    try:
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

    write_json(summary_path, summary)
    write_csv_rows(per_sample_csv_path, per_sample_rows)
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


def run_ragas_evaluation(rows: List[Dict[str, Any]]) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
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

    judge_api_key = str(os.getenv("RAGAS_JUDGE_API_KEY") or os.getenv("MINIMAX_API_KEY") or "").strip()
    judge_base_url = str(os.getenv("RAGAS_JUDGE_BASE_URL") or os.getenv("MINIMAX_BASE_URL") or "https://api.minimaxi.com/v1").strip()
    judge_model = str(os.getenv("RAGAS_JUDGE_MODEL") or os.getenv("MINIMAX_MODEL") or "MiniMax-M2.7").strip()
    if not judge_api_key:
        raise RuntimeError("RAGAS judge API key is empty. Set RAGAS_JUDGE_API_KEY or MINIMAX_API_KEY.")

    dataset = Dataset.from_list(rows)
    judge_llm = ChatOpenAI(
        model=judge_model,
        api_key=judge_api_key,
        base_url=judge_base_url,
        temperature=0,
    )
    ragas_llm = LangchainLLMWrapper(judge_llm)

    metrics = [faithfulness]
    if all(bool(str(row.get("ground_truth") or "").strip()) for row in rows):
        metrics.extend([context_precision, context_recall])

    result = evaluate(dataset, metrics=metrics, llm=ragas_llm)
    dataframe = result.to_pandas()
    per_sample_rows = dataframe.to_dict(orient="records")
    summary = build_ragas_summary(per_sample_rows, judge_model, judge_base_url)
    return summary, per_sample_rows


def build_ragas_summary(per_sample_rows: List[Dict[str, Any]], judge_model: str, judge_base_url: str) -> Dict[str, Any]:
    score_keys = collect_numeric_score_keys(per_sample_rows)
    scores: Dict[str, float] = {}
    for key in score_keys:
        values = [coerce_float(row.get(key)) for row in per_sample_rows]
        numeric_values = [value for value in values if value is not None]
        if not numeric_values:
            continue
        scores[key] = round(sum(numeric_values) / len(numeric_values), 6)

    return {
        "record_count": len(per_sample_rows),
        "metrics": sorted(scores.keys()),
        "scores": scores,
        "judge_provider": "openai_compatible",
        "judge_model": judge_model,
        "judge_base_url": judge_base_url,
    }


def collect_numeric_score_keys(per_sample_rows: List[Dict[str, Any]]) -> List[str]:
    score_keys = set()
    ignored = {"sample_id", "question", "answer", "ground_truth", "contexts"}
    for row in per_sample_rows:
        for key, value in row.items():
            if key in ignored:
                continue
            if coerce_float(value) is not None:
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
