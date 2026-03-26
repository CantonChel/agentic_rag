"""Command line entrypoint for stage-1 benchmark utilities."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from agentic_rag_benchmark.pipeline import build_benchmark_package
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

    return parser


def main(argv: list[str] | None = None) -> int:
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
    else:
        parser.error(f"Unsupported command: {args.command}")
        return 2

    for message in result.messages:
        print(f"[{message.level}] {message.message}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
