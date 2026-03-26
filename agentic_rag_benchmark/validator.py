"""Validation helpers for package files and legacy datasets."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import List

from jsonschema import Draft202012Validator

from .legacy_import import load_legacy_dataset
from .package_spec import STANDARD_PACKAGE_FILES
from .schema_store import load_schema


@dataclass(frozen=True)
class ValidationMessage:
    level: str
    message: str


@dataclass(frozen=True)
class ValidationResult:
    ok: bool
    messages: List[ValidationMessage]


def validate_package_dir(package_dir: Path) -> ValidationResult:
    messages: List[ValidationMessage] = []
    if not package_dir.exists() or not package_dir.is_dir():
        return ValidationResult(False, [ValidationMessage("error", f"Package directory not found: {package_dir}")])

    missing_files = []
    for file_name in STANDARD_PACKAGE_FILES.values():
        file_path = package_dir / file_name
        if not file_path.exists():
            missing_files.append(file_name)

    if missing_files:
        for file_name in missing_files:
            messages.append(ValidationMessage("error", f"Missing required file: {file_name}"))
        return ValidationResult(False, messages)

    manifest_result = validate_optional_json_file(package_dir / STANDARD_PACKAGE_FILES["suite_manifest"], "suite_manifest")
    messages.extend(manifest_result.messages)

    evidence_result = validate_optional_jsonl_file(package_dir / STANDARD_PACKAGE_FILES["evidence_units"], "evidence_unit")
    messages.extend(evidence_result.messages)

    sample_result = validate_optional_jsonl_file(package_dir / STANDARD_PACKAGE_FILES["benchmark_suite"], "benchmark_sample")
    messages.extend(sample_result.messages)

    markdown_path = package_dir / STANDARD_PACKAGE_FILES["review_markdown"]
    if markdown_path.stat().st_size == 0:
        messages.append(ValidationMessage("info", f"Markdown review file is empty: {markdown_path.name}"))

    ok = not any(msg.level == "error" for msg in messages)
    if ok:
        messages.insert(0, ValidationMessage("info", f"Package structure is valid: {package_dir}"))
    return ValidationResult(ok, messages)


def validate_legacy_dataset(dataset_path: Path) -> ValidationResult:
    try:
        samples = load_legacy_dataset(dataset_path)
    except Exception as exc:
        return ValidationResult(False, [ValidationMessage("error", str(exc))])
    return ValidationResult(
        True,
        [
            ValidationMessage("info", f"Legacy dataset import succeeded: {dataset_path}"),
            ValidationMessage("info", f"Imported sample count: {len(samples)}"),
        ],
    )


def describe_schema(schema_name: str | None = None) -> ValidationResult:
    if schema_name:
        schema = load_schema(schema_name)
        required = schema.get("required", [])
        return ValidationResult(
            True,
            [
                ValidationMessage("info", f"schema={schema_name}"),
                ValidationMessage("info", f"required={','.join(required)}"),
            ],
        )

    messages: List[ValidationMessage] = []
    for name in ("evidence_unit", "benchmark_sample", "build_descriptor", "turn_execution_summary", "suite_manifest"):
        schema = load_schema(name)
        required = schema.get("required", [])
        messages.append(ValidationMessage("info", f"{name}: {','.join(required)}"))
    return ValidationResult(True, messages)


def validate_optional_json_file(file_path: Path, schema_name: str) -> ValidationResult:
    if file_path.stat().st_size == 0:
        return ValidationResult(True, [ValidationMessage("info", f"Skipping empty JSON file: {file_path.name}")])
    try:
        payload = json.loads(file_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return ValidationResult(False, [ValidationMessage("error", f"Invalid JSON in {file_path.name}: {exc}")])
    return validate_instance(payload, schema_name, file_path.name)


def validate_optional_jsonl_file(file_path: Path, schema_name: str) -> ValidationResult:
    if file_path.stat().st_size == 0:
        return ValidationResult(True, [ValidationMessage("info", f"Skipping empty JSONL file: {file_path.name}")])

    messages: List[ValidationMessage] = []
    ok = True
    for line_no, line in enumerate(file_path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text:
            continue
        try:
            payload = json.loads(text)
        except Exception as exc:
            messages.append(ValidationMessage("error", f"Invalid JSONL in {file_path.name} line {line_no}: {exc}"))
            ok = False
            continue

        result = validate_instance(payload, schema_name, f"{file_path.name}:{line_no}")
        messages.extend(result.messages)
        ok = ok and result.ok
    return ValidationResult(ok, messages)


def validate_instance(payload: object, schema_name: str, source_name: str) -> ValidationResult:
    schema = load_schema(schema_name)
    validator = Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(payload), key=lambda item: list(item.absolute_path))
    if not errors:
        return ValidationResult(True, [ValidationMessage("info", f"{source_name} matches {schema_name}")])

    messages = []
    for error in errors:
        path = ".".join(str(part) for part in error.absolute_path)
        location = path or "<root>"
        messages.append(ValidationMessage("error", f"{source_name} invalid at {location}: {error.message}"))
    return ValidationResult(False, messages)
