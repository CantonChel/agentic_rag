"""Write portable gold benchmark packages to disk."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from datetime import timezone
import json
from pathlib import Path
from typing import Iterable
from typing import List

from .contracts import AuthoringBlock
from .contracts import BenchmarkSample
from .contracts import BlockLink
from .contracts import GoldPackageManifest
from .contracts import SampleGenerationTrace
from .contracts import SourceManifest
from .normalizer import NormalizedDocument
from .package_spec import PACKAGE_VERSION
from .package_spec import STANDARD_PACKAGE_FILES


@dataclass(frozen=True)
class PackageWriteResult:
    package_dir: Path
    authoring_block_count: int
    sample_count: int


class PackageWriter:
    """Persist gold authoring assets using the stage-1 package layout."""

    def __init__(self, generator_version: str = "gold_stage1_v1") -> None:
        self.generator_version = generator_version

    def write_package(
        self,
        package_dir: Path,
        project_key: str,
        suite_version: str,
        source_manifest: SourceManifest,
        normalized_documents: Iterable[NormalizedDocument],
        authoring_blocks: Iterable[AuthoringBlock],
        block_links: Iterable[BlockLink],
        benchmark_samples: Iterable[BenchmarkSample],
        sample_generation_traces: Iterable[SampleGenerationTrace],
    ) -> PackageWriteResult:
        package_dir.mkdir(parents=True, exist_ok=True)

        document_list = list(normalized_documents)
        block_list = list(authoring_blocks)
        block_link_list = list(block_links)
        sample_list = list(benchmark_samples)
        trace_list = list(sample_generation_traces)

        write_json(
            package_dir / STANDARD_PACKAGE_FILES["source_manifest"],
            source_manifest.to_dict(),
        )
        write_jsonl(
            package_dir / STANDARD_PACKAGE_FILES["normalized_documents"],
            [item.to_dict() for item in document_list],
        )
        write_jsonl(
            package_dir / STANDARD_PACKAGE_FILES["authoring_blocks"],
            [item.to_dict() for item in block_list],
        )
        write_jsonl(
            package_dir / STANDARD_PACKAGE_FILES["block_links"],
            [item.to_dict() for item in block_link_list],
        )
        write_jsonl(
            package_dir / STANDARD_PACKAGE_FILES["samples"],
            [item.to_dict() for item in sample_list],
        )
        write_jsonl(
            package_dir / STANDARD_PACKAGE_FILES["sample_generation_trace"],
            [item.to_dict() for item in trace_list],
        )

        manifest = GoldPackageManifest(
            package_version=PACKAGE_VERSION,
            project_key=project_key,
            suite_version=suite_version,
            created_at=datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            generator_version=self.generator_version,
            files=dict(STANDARD_PACKAGE_FILES),
        )
        write_json(
            package_dir / STANDARD_PACKAGE_FILES["gold_package_manifest"],
            manifest.to_dict(),
        )
        (package_dir / STANDARD_PACKAGE_FILES["review_markdown"]).write_text(
            render_review_markdown(project_key, suite_version, block_list, sample_list),
            encoding="utf-8",
        )

        return PackageWriteResult(
            package_dir=package_dir,
            authoring_block_count=len(block_list),
            sample_count=len(sample_list),
        )


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: List[dict]) -> None:
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def render_review_markdown(
    project_key: str,
    suite_version: str,
    authoring_blocks: List[AuthoringBlock],
    benchmark_samples: List[BenchmarkSample],
) -> str:
    blocks_by_id = {item.block_id: item for item in authoring_blocks}
    lines: List[str] = [
        "# Gold Package Review",
        "",
        f"- project_key: `{project_key}`",
        f"- suite_version: `{suite_version}`",
        f"- authoring_block_count: `{len(authoring_blocks)}`",
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
                "### Gold Block 引用",
                "",
            ]
        )

        for ref in sample.gold_block_refs:
            block = blocks_by_id.get(ref.block_id)
            label = block.section_title if block else ref.section_key
            lines.append(f"- `{ref.block_id}` [{ref.doc_path}] #{label}")

        lines.extend(["", "### 标准上下文", ""])
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

        lines.extend(["### 对应 Block 正文", ""])
        for ref in sample.gold_block_refs:
            block = blocks_by_id.get(ref.block_id)
            if block is None:
                continue
            lines.extend(
                [
                    f"#### Block `{block.block_id}`",
                    "",
                    "```text",
                    block.text,
                    "```",
                    "",
                ]
            )

    return "\n".join(lines).rstrip() + "\n"
