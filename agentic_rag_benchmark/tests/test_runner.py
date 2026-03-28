import json
import tempfile
import unittest
from pathlib import Path

from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import run_benchmark
from agentic_rag_benchmark.runner_io import load_benchmark_package


class RunnerPackageLoadTest(unittest.TestCase):
    def test_load_benchmark_package_reads_manifest_samples_and_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = Path(tmp_dir) / "packages" / "api_docs" / "base_v1"
            package_dir.mkdir(parents=True)
            self._write_package(package_dir)

            loaded = load_benchmark_package(package_dir)

            self.assertEqual(loaded.manifest.project_key, "api_docs")
            self.assertEqual(loaded.manifest.suite_version, "base_v1")
            self.assertEqual(len(loaded.evidence_units), 1)
            self.assertEqual(len(loaded.benchmark_samples), 1)
            self.assertEqual(loaded.benchmark_samples[0].sample_id, "sample_1")

    def test_run_benchmark_prepares_pending_sample_results(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            package_dir = root / "packages" / "api_docs" / "base_v1"
            package_dir.mkdir(parents=True)
            self._write_package(package_dir)

            request = RunBenchmarkRequest(
                package_dir=package_dir,
                base_url="http://127.0.0.1:8081",
                provider="openai",
                build_id="build_123",
                user_id="bench-user",
                session_prefix="bench",
                timeout_seconds=180,
                output_root=root / "outputs",
            )

            report = run_benchmark(request)

            self.assertEqual(report.project_key, "api_docs")
            self.assertEqual(report.sample_count, 1)
            self.assertEqual(report.evidence_count, 1)
            self.assertEqual(len(report.sample_results), 1)
            self.assertEqual(report.sample_results[0].provider, "openai")
            self.assertEqual(report.sample_results[0].build_id, "build_123")
            self.assertEqual(report.sample_results[0].session_id, "bench-sample_1")
            self.assertIsNone(report.sample_results[0].turn_id)
            self.assertEqual(report.sample_results[0].final_answer, "")

    def _write_package(self, package_dir: Path) -> None:
        manifest = {
            "package_version": "v1",
            "project_key": "api_docs",
            "suite_version": "base_v1",
            "created_at": "2026-03-28T00:00:00Z",
            "generator_version": "stage2",
            "files": dict(STANDARD_PACKAGE_FILES),
        }
        evidence = {
            "evidence_id": "e1",
            "doc_path": "docs/sample.md",
            "section_key": "overview",
            "section_title": "Overview",
            "canonical_text": "sample context",
            "anchor": "docs/sample.md#overview",
            "source_hash": "hash1",
            "extractor_version": "stage2_v1",
        }
        sample = {
            "sample_id": "sample_1",
            "question": "What is sample context?",
            "ground_truth": "sample answer",
            "ground_truth_contexts": ["sample context"],
            "gold_evidence_refs": [
                {
                    "evidence_id": "e1",
                    "doc_path": "docs/sample.md",
                    "section_key": "overview",
                }
            ],
            "tags": ["api"],
            "difficulty": "easy",
            "suite_version": "base_v1",
        }
        (package_dir / STANDARD_PACKAGE_FILES["suite_manifest"]).write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        (package_dir / STANDARD_PACKAGE_FILES["evidence_units"]).write_text(
            json.dumps(evidence, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        (package_dir / STANDARD_PACKAGE_FILES["benchmark_suite"]).write_text(
            json.dumps(sample, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        (package_dir / STANDARD_PACKAGE_FILES["review_markdown"]).write_text(
            "# Benchmark Review\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
