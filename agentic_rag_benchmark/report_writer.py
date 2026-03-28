"""Report builders and output writers for benchmark runs."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from typing import Dict
from typing import List

from .runner import RunBenchmarkReport
from .runner import RunBenchmarkSampleResult


@dataclass(frozen=True)
class BenchmarkReportArtifacts:
    """Paths produced by one benchmark run."""

    output_dir: Path
    run_meta_path: Path
    samples_collected_path: Path
    benchmark_report_json_path: Path
    benchmark_report_markdown_path: Path


def write_benchmark_outputs(report: RunBenchmarkReport) -> BenchmarkReportArtifacts:
    """Write benchmark outputs under outputs/run_<timestamp>/."""

    output_root = report.request.output_root
    output_root.mkdir(parents=True, exist_ok=True)
    output_dir = output_root / f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    output_dir.mkdir(parents=True, exist_ok=False)

    run_meta = build_run_meta(report)
    summary = build_report_summary(report.sample_results)
    failed_samples = build_failed_samples(report.sample_results)

    run_meta_path = output_dir / "run_meta.json"
    samples_collected_path = output_dir / "samples_collected.jsonl"
    benchmark_report_json_path = output_dir / "benchmark_report.json"
    benchmark_report_markdown_path = output_dir / "benchmark_report.md"

    write_json(run_meta_path, run_meta)
    write_jsonl(samples_collected_path, [sample.to_dict() for sample in report.sample_results])
    write_json(
        benchmark_report_json_path,
        {
            "run_meta": run_meta,
            "summary": summary,
            "samples": [sample.to_dict() for sample in report.sample_results],
            "failed_samples": failed_samples,
        },
    )
    benchmark_report_markdown_path.write_text(
        build_markdown_report(run_meta, summary, report.sample_results, failed_samples),
        encoding="utf-8",
    )

    return BenchmarkReportArtifacts(
        output_dir=output_dir,
        run_meta_path=run_meta_path,
        samples_collected_path=samples_collected_path,
        benchmark_report_json_path=benchmark_report_json_path,
        benchmark_report_markdown_path=benchmark_report_markdown_path,
    )


def build_run_meta(report: RunBenchmarkReport) -> Dict[str, Any]:
    return {
        "package_dir": str(report.request.package_dir) if report.request.package_dir else None,
        "legacy_dataset": str(report.request.legacy_dataset) if report.request.legacy_dataset else None,
        "project_key": report.project_key,
        "suite_version": report.suite_version,
        "provider": report.request.provider,
        "build_id": report.request.build_id,
        "sample_count": report.sample_count,
        "started_at": report.started_at,
        "completed_at": report.completed_at,
    }


def build_report_summary(sample_results: List[RunBenchmarkSampleResult]) -> Dict[str, Any]:
    sample_count = len(sample_results)
    success_count = sum(1 for sample in sample_results if is_successful_sample(sample))
    latency_values = [sample.latency_ms for sample in sample_results if sample.latency_ms is not None]
    finish_reason_distribution: Dict[str, int] = {}
    context_output_sample_count = 0
    context_output_chunk_count = 0

    for sample in sample_results:
        finish_reason = sample.finish_reason or "unknown"
        finish_reason_distribution[finish_reason] = finish_reason_distribution.get(finish_reason, 0) + 1

        context_hits = count_context_output_hits(sample)
        if context_hits > 0:
            context_output_sample_count += 1
            context_output_chunk_count += context_hits

    average_latency_ms = round(sum(latency_values) / len(latency_values), 2) if latency_values else None
    success_rate = round((success_count / sample_count) * 100, 2) if sample_count else 0.0

    return {
        "sample_count": sample_count,
        "success_count": success_count,
        "failure_count": sample_count - success_count,
        "success_rate": success_rate,
        "average_latency_ms": average_latency_ms,
        "finish_reason_distribution": finish_reason_distribution,
        "retrieval_hit_overview": {
            "samples_with_context_output": context_output_sample_count,
            "context_output_chunk_count": context_output_chunk_count,
        },
    }


def build_failed_samples(sample_results: List[RunBenchmarkSampleResult]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for sample in sample_results:
        if is_successful_sample(sample):
            continue
        out.append(
            {
                "sample_id": sample.sample_id,
                "question": sample.question,
                "finish_reason": sample.finish_reason,
                "error": sample.error,
                "turn_id": sample.turn_id,
                "trace_id": sample.trace_id,
            }
        )
    return out


def build_markdown_report(
    run_meta: Dict[str, Any],
    summary: Dict[str, Any],
    sample_results: List[RunBenchmarkSampleResult],
    failed_samples: List[Dict[str, Any]],
) -> str:
    lines = [
        "# Benchmark Report",
        "",
        "## 运行摘要",
        f"- package_dir: `{run_meta['package_dir']}`",
        f"- legacy_dataset: `{run_meta['legacy_dataset']}`",
        f"- project_key: `{run_meta['project_key']}`",
        f"- suite_version: `{run_meta['suite_version']}`",
        f"- provider: `{run_meta['provider']}`",
        f"- build_id: `{run_meta['build_id']}`",
        f"- sample_count: `{run_meta['sample_count']}`",
        f"- started_at: `{run_meta['started_at']}`",
        f"- completed_at: `{run_meta['completed_at']}`",
        "",
        "## 总体成功率",
        f"- success_count: `{summary['success_count']}` / `{summary['sample_count']}`",
        f"- success_rate: `{summary['success_rate']}%`",
        "",
        "## 平均耗时",
        f"- average_latency_ms: `{summary['average_latency_ms']}`",
        "",
        "## finish reason 分布",
    ]

    finish_reason_distribution = summary["finish_reason_distribution"]
    if finish_reason_distribution:
        lines.append("| finish_reason | count |")
        lines.append("| --- | ---: |")
        for finish_reason, count in finish_reason_distribution.items():
            lines.append(f"| {finish_reason} | {count} |")
    else:
        lines.append("- 无")

    lines.extend(
        [
            "",
            "## 失败样本列表",
        ]
    )
    if failed_samples:
        lines.append("| sample_id | finish_reason | error |")
        lines.append("| --- | --- | --- |")
        for sample in failed_samples:
            lines.append(
                f"| {sample['sample_id']} | {sample['finish_reason'] or 'unknown'} | {sample['error'] or ''} |"
            )
    else:
        lines.append("- 无")

    retrieval_hit_overview = summary["retrieval_hit_overview"]
    lines.extend(
        [
            "",
            "## 检索命中概览",
            f"- samples_with_context_output: `{retrieval_hit_overview['samples_with_context_output']}` / `{len(sample_results)}`",
            f"- context_output_chunk_count: `{retrieval_hit_overview['context_output_chunk_count']}`",
        ]
    )
    return "\n".join(lines) + "\n"


def count_context_output_hits(sample: RunBenchmarkSampleResult) -> int:
    count = 0
    for record in sample.retrieval_trace_records:
        if record.get("stage") == "context_output" and record.get("recordType") == "chunk":
            count += 1
    return count


def is_successful_sample(sample: RunBenchmarkSampleResult) -> bool:
    if sample.error:
        return False
    if not sample.turn_id:
        return False
    return bool(sample.final_answer)


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_jsonl(path: Path, rows: List[Dict[str, Any]]) -> None:
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
