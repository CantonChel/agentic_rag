"""Schema loading helpers for agentic_rag_benchmark."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict
from typing import Iterable


SCHEMA_DIR = Path(__file__).resolve().parent / "schemas"
SCHEMA_FILES: Dict[str, str] = {
    "source_manifest": "source_manifest.schema.json",
    "normalized_document": "normalized_document.schema.json",
    "authoring_block": "authoring_block.schema.json",
    "block_link": "block_link.schema.json",
    "benchmark_sample": "benchmark_sample.schema.json",
    "sample_generation_trace": "sample_generation_trace.schema.json",
    "gold_package_manifest": "gold_package_manifest.schema.json",
    "build_descriptor": "build_descriptor.schema.json",
    "turn_execution_summary": "turn_execution_summary.schema.json",
}


def load_schema(schema_name: str) -> Dict[str, object]:
    file_name = SCHEMA_FILES.get(schema_name)
    if not file_name:
        raise KeyError(f"Unknown schema: {schema_name}")
    schema_path = SCHEMA_DIR / file_name
    return json.loads(schema_path.read_text(encoding="utf-8"))


def list_schema_names() -> Iterable[str]:
    return SCHEMA_FILES.keys()
