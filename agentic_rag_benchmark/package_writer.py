"""Write portable benchmark packages to disk."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import json
from pathlib import Path
from typing import Iterable
from typing import List

from .contracts import BenchmarkSample
from .contracts import EvidenceUnit
from .contracts import SuiteManifest
from .package_spec import PACKAGE_VERSION
from .package_spec import STANDARD_PACKAGE_FILES


@dataclass(frozen=True)
class PackageWriteResult:
    package_dir: Path
    evidence_count: int
    sample_count: int


class PackageWriter:
    """Persist evidence and benchmark samples using the standard package layout."""

    def __init__(self, generator_version: str = "stage2_v1") -> None:
        self.generator_version = generator_version

    def write_package(
        self,
        package_dir: Path,
        project_key: str,
        suite_version: str,
        evidence_units: Iterable[EvidenceUnit],
        benchmark_samples: Iterable[BenchmarkSample],
    ) -> PackageWriteResult:
        package_dir.mkdir(parents=True, exist_ok=True)

        evidence_list = list(evidence_units)
        sample_list = list(benchmark_samples)

        evidence_path = package_dir / STANDARD_PACKAGE_FILES["evidence_units"]
        sample_path = package_dir / STANDARD_PACKAGE_FILES["benchmark_suite"]
        manifest_path = package_dir / STANDARD_PACKAGE_FILES["suite_manifest"]
        review_path = package_dir / STANDARD_PACKAGE_FILES["review_markdown"]

        write_jsonl(evidence_path, [item.to_dict() for item in evidence_list])
        write_jsonl(sample_path, [item.to_dict() for item in sample_list])

        manifest = SuiteManifest(
            package_version=PACKAGE_VERSION,
            project_key=project_key,
            suite_version=suite_version,
            created_at=datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
            generator_version=self.generator_version,
            files=dict(STANDARD_PACKAGE_FILES),
        )
        manifest_path.write_text(json.dumps(manifest.to_dict(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        review_path.write_text(
            render_review_markdown(project_key, suite_version, evidence_list, sample_list),
            encoding="utf-8",
        )

        return PackageWriteResult(package_dir=package_dir, evidence_count=len(evidence_list), sample_count=len(sample_list))


def write_jsonl(path: Path, rows: List[dict]) -> None:
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def render_review_markdown(
    project_key: str,
    suite_version: str,
    evidence_units: List[EvidenceUnit],
    benchmark_samples: List[BenchmarkSample],
) -> str:
    evidence_by_id = {item.evidence_id: item for item in evidence_units}
    lines: List[str] = [
        "# Benchmark Review",
        "",
        f"- project_key: `{project_key}`",
        f"- suite_version: `{suite_version}`",
        f"- evidence_count: `{len(evidence_units)}`",
        f"- sample_count: `{len(benchmark_samples)}`",
        "",
    ]

    for index, sample in enumerate(benchmark_samples, start=1):
        lines.extend(
            [
                f"## {index}. {sample.question}",
                "",
                f"- sample_id: `{sample.sample_id}`",
                f"- difficulty: `{sample.difficulty}`",
                f"- tags: `{', '.join(sample.tags)}`" if sample.tags else "- tags: ``",
                "",
                "### 标准答案",
                "",
                sample.ground_truth,
                "",
                "### 证据引用",
                "",
            ]
        )

        for ref in sample.gold_evidence_refs:
            evidence = evidence_by_id.get(ref.evidence_id)
            section_title = evidence.section_title if evidence else ref.section_key
            lines.append(f"- `{ref.evidence_id}` [{ref.doc_path}] #{section_title}")

        lines.extend(["", "### 标准证据", ""])
        for context_index, context in enumerate(sample.ground_truth_contexts, start=1):
            lines.extend(
                [
                    f"#### Context {context_index}",
                    "",
                    "```text",
                    context,
                    "```",
                    "",
                ]
            )

    return "\n".join(lines).rstrip() + "\n"
