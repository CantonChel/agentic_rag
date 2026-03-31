"""Command line entrypoint for stage-1 benchmark utilities."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from agentic_rag_benchmark.env_loader import load_dotenv_defaults
from agentic_rag_benchmark.pipeline import build_benchmark_package
from agentic_rag_benchmark.ragas_runner import evaluate_ragas_for_report
from agentic_rag_benchmark.report_writer import write_benchmark_outputs
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import run_benchmark
from agentic_rag_benchmark.validator import describe_schema
from agentic_rag_benchmark.validator import validate_legacy_dataset
from agentic_rag_benchmark.validator import validate_package_dir


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="agentic_rag_benchmark", description="Benchmark utilities")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_package = subparsers.add_parser("validate-package", help="Validate one portable package directory")
    validate_package.add_argument("package_dir", help="Path to packages/<project_key>/<suite_version>")

    validate_legacy = subparsers.add_parser("validate-legacy-dataset", help="Validate one legacy JSONL dataset")
    validate_legacy.add_argument("dataset_path", help="Path to legacy JSONL dataset")

    describe = subparsers.add_parser("describe-schema", help="Show required fields for one schema or all schemas")
    describe.add_argument("--schema", default=None, help="Optional schema name")

    build_package = subparsers.add_parser("build-package", help="Build a portable benchmark package from source documents")
    build_package.add_argument("source_path", help="Source file or source directory")
    build_package.add_argument("--project-key", required=True, help="Generic project key used under packages/")
    build_package.add_argument("--suite-version", default="base_v1", help="Suite version name")
    build_package.add_argument(
        "--package-root",
        default=str(Path(__file__).resolve().parents[1]),
        help="Root directory containing the packages/ folder",
    )
    build_package.add_argument(
        "--docreader-base-url",
        default=os.getenv("DOCREADER_BASE_URL", "http://127.0.0.1:8090"),
        help="Base URL for docreader service",
    )
    build_package.add_argument(
        "--source-root",
        default=None,
        help="Optional root path used to compute relative doc_path values",
    )

    run_benchmark_parser = subparsers.add_parser("run-benchmark", help="Run benchmark against one imported build")
    input_group = run_benchmark_parser.add_mutually_exclusive_group(required=True)
    input_group.add_argument("--package-dir", help="Path to packages/<project_key>/<suite_version>")
    input_group.add_argument("--legacy-dataset", help="Path to one legacy JSONL dataset")
    run_benchmark_parser.add_argument(
        "--base-url",
        default=os.getenv("APP_BASE_URL", "http://127.0.0.1:8081"),
        help="Base URL for agentic_rag_app",
    )
    run_benchmark_parser.add_argument("--provider", required=True, help="Agent provider name")
    run_benchmark_parser.add_argument("--build-id", required=True, help="Benchmark build id to evaluate")
    run_benchmark_parser.add_argument("--user-id", required=True, help="User id used for benchmark requests")
    run_benchmark_parser.add_argument("--session-prefix", default="benchmark", help="Session id prefix")
    run_benchmark_parser.add_argument("--timeout-seconds", type=int, default=180, help="Request timeout in seconds")
    run_benchmark_parser.add_argument(
        "--output-root",
        default=str(Path(__file__).resolve().parents[1] / "outputs"),
        help="Directory used for benchmark outputs",
    )
    run_benchmark_parser.add_argument(
        "--verify-ssl",
        action="store_true",
        help="Verify HTTPS certificates for app requests",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    load_dotenv_defaults(Path(__file__).resolve())
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "validate-package":
        result = validate_package_dir(Path(args.package_dir))
    elif args.command == "validate-legacy-dataset":
        result = validate_legacy_dataset(Path(args.dataset_path))
    elif args.command == "describe-schema":
        result = describe_schema(args.schema)
    elif args.command == "build-package":
        report = build_benchmark_package(
            source_path=Path(args.source_path),
            project_key=args.project_key,
            suite_version=args.suite_version,
            package_root=Path(args.package_root),
            docreader_base_url=args.docreader_base_url,
            source_root=Path(args.source_root) if args.source_root else None,
        )
        for line in (
            f"[info] Package written to: {report.package_dir}",
            f"[info] Source file count: {len(report.source_files)}",
            f"[info] Normalized document count: {report.normalized_document_count}",
            f"[info] Evidence count: {report.evidence_count}",
            f"[info] Sample count: {report.sample_count}",
        ):
            print(line)
        return 0
    elif args.command == "run-benchmark":
        request = RunBenchmarkRequest(
            package_dir=Path(args.package_dir) if args.package_dir else None,
            base_url=args.base_url,
            provider=args.provider,
            build_id=args.build_id,
            user_id=args.user_id,
            session_prefix=args.session_prefix,
            timeout_seconds=args.timeout_seconds,
            output_root=Path(args.output_root),
            legacy_dataset=Path(args.legacy_dataset) if args.legacy_dataset else None,
            verify_ssl=bool(args.verify_ssl),
        )
        report = run_benchmark(request)
        artifacts = write_benchmark_outputs(report)
        ragas_artifacts = evaluate_ragas_for_report(report, artifacts.output_dir)
        for line in (
            f"[info] Benchmark input package: {request.package_dir or 'n/a'}",
            f"[info] Benchmark input legacy dataset: {request.legacy_dataset or 'n/a'}",
            f"[info] Project key: {report.project_key}",
            f"[info] Suite version: {report.suite_version}",
            f"[info] Evidence count: {report.evidence_count}",
            f"[info] Sample count: {report.sample_count}",
            f"[info] Output directory: {artifacts.output_dir}",
            f"[info] RAGAS summary: {ragas_artifacts.summary_path or 'not generated'}",
            f"[info] RAGAS error: {ragas_artifacts.error_path or 'none'}",
            f"[info] Execution mode: evalMode={request.eval_mode}, kbScope={request.kb_scope}, memoryEnabled={str(request.memory_enabled).lower()}, thinkingProfile={request.thinking_profile}",
        ):
            print(line)
        return 0
    else:
        parser.error(f"Unsupported command: {args.command}")
        return 2

    for message in result.messages:
        print(f"[{message.level}] {message.message}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
