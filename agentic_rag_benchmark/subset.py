"""Helpers for building temporary benchmark subset packages."""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path
from typing import List

from .contracts import BenchmarkSample
from .contracts import EvidenceUnit
from .package_spec import build_package_dir
from .package_writer import PackageWriter
from .runner_io import load_benchmark_package
from .validator import validate_package_dir


@dataclass(frozen=True)
class SubsetPackageResult:
    package_dir: Path
    project_key: str
    suite_version: str
    seed: int
    total_sample_count: int
    selected_sample_count: int
    selected_evidence_count: int


def create_random_subset_package(
    package_dir: Path,
    sample_count: int,
    seed: int,
    output_root: Path,
    suite_version_suffix: str,
) -> SubsetPackageResult:
    loaded = load_benchmark_package(package_dir)
    total_sample_count = len(loaded.benchmark_samples)
    if sample_count <= 0:
        raise ValueError("sample_count must be greater than 0")
    if sample_count > total_sample_count:
        raise ValueError(f"sample_count {sample_count} exceeds available samples {total_sample_count}")

    selected_samples = select_random_samples(loaded.benchmark_samples, sample_count=sample_count, seed=seed)
    selected_evidence_ids = {
        ref.evidence_id
        for sample in selected_samples
        for ref in sample.gold_evidence_refs
    }
    selected_evidence_units = [item for item in loaded.evidence_units if item.evidence_id in selected_evidence_ids]

    suite_version = f"{loaded.manifest.suite_version}_{suite_version_suffix}"
    subset_dir = build_package_dir(output_root, loaded.manifest.project_key, suite_version)
    writer = PackageWriter(generator_version="console_subset_v1")
    writer.write_package(
        package_dir=subset_dir,
        project_key=loaded.manifest.project_key,
        suite_version=suite_version,
        evidence_units=selected_evidence_units,
        benchmark_samples=selected_samples,
    )
    validation = validate_package_dir(subset_dir)
    if not validation.ok:
        errors = [message.message for message in validation.messages if message.level == "error"]
        raise ValueError("; ".join(errors) or f"Invalid subset package: {subset_dir}")

    return SubsetPackageResult(
        package_dir=subset_dir,
        project_key=loaded.manifest.project_key,
        suite_version=suite_version,
        seed=seed,
        total_sample_count=total_sample_count,
        selected_sample_count=len(selected_samples),
        selected_evidence_count=len(selected_evidence_units),
    )


def select_random_samples(samples: List[BenchmarkSample], sample_count: int, seed: int) -> List[BenchmarkSample]:
    if sample_count >= len(samples):
        return list(samples)
    rng = random.Random(seed)
    selected_indices = sorted(rng.sample(range(len(samples)), sample_count))
    return [samples[index] for index in selected_indices]
