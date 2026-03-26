"""Command line entrypoint for stage-1 benchmark utilities."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from agentic_rag_benchmark.validator import describe_schema
from agentic_rag_benchmark.validator import validate_legacy_dataset
from agentic_rag_benchmark.validator import validate_package_dir


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="agentic_rag_benchmark", description="Stage-1 benchmark utilities")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_package = subparsers.add_parser("validate-package", help="Validate one portable package directory")
    validate_package.add_argument("package_dir", help="Path to packages/<project_key>/<suite_version>")

    validate_legacy = subparsers.add_parser("validate-legacy-dataset", help="Validate one legacy JSONL dataset")
    validate_legacy.add_argument("dataset_path", help="Path to legacy JSONL dataset")

    describe = subparsers.add_parser("describe-schema", help="Show required fields for one schema or all schemas")
    describe.add_argument("--schema", default=None, help="Optional schema name")

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
    else:
        parser.error(f"Unsupported command: {args.command}")
        return 2

    for message in result.messages:
        print(f"[{message.level}] {message.message}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
