from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from typing import List

from agentic_rag_benchmark.contracts import AuthoringBlock
from agentic_rag_benchmark.contracts import BenchmarkSample
from agentic_rag_benchmark.contracts import GoldBlockReference
from agentic_rag_benchmark.contracts import SampleGenerationTrace
from agentic_rag_benchmark.contracts import SourceFileRecord
from agentic_rag_benchmark.contracts import SourceManifest
from agentic_rag_benchmark.normalizer import NormalizedBlock
from agentic_rag_benchmark.normalizer import NormalizedDocument
from agentic_rag_benchmark.package_writer import PackageWriter


@dataclass(frozen=True)
class GoldPackageSpec:
    block_id: str
    doc_path: str
    section_key: str
    section_title: str
    text: str
    question: str
    ground_truth: str
    tags: List[str]
    difficulty: str = "medium"
    heading_level: int = 2
    block_type: str = "section"


def write_gold_package(
    package_dir: Path,
    project_key: str,
    suite_version: str,
    specs: Iterable[GoldPackageSpec],
    generator_version: str = "test_gold_package",
) -> Path:
    spec_list = list(specs)
    source_manifest = SourceManifest(
        source_set_id=f"{project_key}-source-set",
        project_key=project_key,
        source_root=str(package_dir.parent),
        file_count=len(spec_list),
        files=[
            SourceFileRecord(
                path=spec.doc_path,
                size_bytes=len(spec.text.encode("utf-8")),
                sha256=f"sha-{index + 1}",
            )
            for index, spec in enumerate(spec_list)
        ],
        created_at="2026-04-07T00:00:00Z",
    )
    normalized_documents = [
        NormalizedDocument(
            doc_path=spec.doc_path,
            title=Path(spec.doc_path).stem,
            normalized_text=spec.text,
            metadata={"source_name": Path(spec.doc_path).name},
            blocks=[
                NormalizedBlock(
                    block_id=spec.block_id,
                    section_key=spec.section_key,
                    section_title=spec.section_title,
                    block_type=spec.block_type,
                    heading_level=spec.heading_level,
                    content=spec.text,
                    start_line=1,
                    end_line=max(1, len(spec.text.splitlines())),
                )
            ],
        )
        for spec in spec_list
    ]
    authoring_blocks = [
        AuthoringBlock(
            block_id=spec.block_id,
            doc_path=spec.doc_path,
            section_key=spec.section_key,
            section_title=spec.section_title,
            block_type=spec.block_type,
            heading_level=spec.heading_level,
            text=spec.text,
            anchor=f"{spec.doc_path}#{spec.section_key}",
            source_hash=f"hash-{index + 1}",
            start_line=1,
            end_line=max(1, len(spec.text.splitlines())),
        )
        for index, spec in enumerate(spec_list)
    ]
    benchmark_samples = [
        BenchmarkSample(
            sample_id=f"sample_{index + 1}",
            question=spec.question,
            ground_truth=spec.ground_truth,
            ground_truth_contexts=[spec.text],
            gold_block_refs=[
                GoldBlockReference(
                    block_id=spec.block_id,
                    doc_path=spec.doc_path,
                    section_key=spec.section_key,
                )
            ],
            tags=list(spec.tags),
            difficulty=spec.difficulty,
            suite_version=suite_version,
        )
        for index, spec in enumerate(spec_list)
    ]
    sample_generation_traces = [
        SampleGenerationTrace(
            sample_id=sample.sample_id,
            generation_method="rule_based",
            input_block_ids=[spec.block_id],
            generator_version="gold_stage1_v1",
            model_or_rule_name="BenchmarkAuthoringStrategy",
            validation_status="generated",
        )
        for spec, sample in zip(spec_list, benchmark_samples)
    ]
    PackageWriter(generator_version=generator_version).write_package(
        package_dir=package_dir,
        project_key=project_key,
        suite_version=suite_version,
        source_manifest=source_manifest,
        normalized_documents=normalized_documents,
        authoring_blocks=authoring_blocks,
        block_links=[],
        benchmark_samples=benchmark_samples,
        sample_generation_traces=sample_generation_traces,
    )
    return package_dir
