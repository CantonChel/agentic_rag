import json
import tempfile
import unittest
from pathlib import Path
from typing import Any
from typing import Optional

from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import run_benchmark
from agentic_rag_benchmark.runner_io import load_benchmark_package
from agentic_rag_benchmark.runner_client import AgentStreamCapture


class FakeBenchmarkAppClient:
    def __init__(
        self,
        stream_capture: Optional[AgentStreamCapture] = None,
        summary_payload: Optional[dict[str, Any]] = None,
        retrieval_payload: Optional[list[dict[str, Any]]] = None,
        summary_error: Optional[Exception] = None,
    ) -> None:
        self.stream_capture = stream_capture or AgentStreamCapture(
            turn_id="turn-1",
            event_count=3,
            event_type_counts={"turn_start": 1, "done": 1, "turn_end": 1},
            transport_error=None,
        )
        self.summary_payload = summary_payload or {
            "turnId": "turn-1",
            "traceId": "trace-1",
            "finalAnswer": "sample answer",
            "finishReason": "stop",
            "latencyMs": 88,
            "toolCalls": [{"toolName": "search_knowledge_base"}],
            "retrievalTraceIds": ["trace-1"],
        }
        self.retrieval_payload = retrieval_payload if retrieval_payload is not None else [
            {"traceId": "trace-1", "stage": "context_output", "recordType": "chunk"}
        ]
        self.summary_error = summary_error

    def stream_agent_turn(self, **_: Any) -> AgentStreamCapture:
        return self.stream_capture

    def get_turn_summary(self, turn_id: str) -> dict[str, Any]:
        if self.summary_error is not None:
            raise self.summary_error
        payload = dict(self.summary_payload)
        payload["turnId"] = payload.get("turnId") or turn_id
        return payload

    def get_retrieval_traces(self, trace_id: str) -> list[dict[str, Any]]:
        return [dict(item, traceId=item.get("traceId", trace_id)) for item in self.retrieval_payload]


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

    def test_run_benchmark_executes_sample_via_turn_summary_and_retrieval_trace(self) -> None:
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

            report = run_benchmark(request, client=FakeBenchmarkAppClient())

            self.assertEqual(report.project_key, "api_docs")
            self.assertEqual(report.sample_count, 1)
            self.assertEqual(report.evidence_count, 1)
            self.assertEqual(len(report.sample_results), 1)
            self.assertEqual(report.sample_results[0].provider, "openai")
            self.assertEqual(report.sample_results[0].build_id, "build_123")
            self.assertTrue(report.sample_results[0].session_id.startswith("bench-sample_1-"))
            self.assertEqual(report.sample_results[0].turn_id, "turn-1")
            self.assertEqual(report.sample_results[0].trace_id, "trace-1")
            self.assertEqual(report.sample_results[0].final_answer, "sample answer")
            self.assertEqual(report.sample_results[0].finish_reason, "stop")
            self.assertEqual(report.sample_results[0].stream_event_count, 3)
            self.assertEqual(len(report.sample_results[0].retrieval_trace_records), 1)
            self.assertIsNone(report.sample_results[0].error)

    def test_run_benchmark_records_explicit_error_when_turn_summary_missing(self) -> None:
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

            report = run_benchmark(request, client=FakeBenchmarkAppClient(summary_error=RuntimeError("turn summary not found")))

            self.assertEqual(report.sample_results[0].turn_id, "turn-1")
            self.assertIn("Failed to fetch turn summary", report.sample_results[0].error)
            self.assertIn("turn summary not found", report.sample_results[0].error)
            self.assertEqual(report.sample_results[0].stream_event_count, 3)

    def test_run_benchmark_allows_empty_retrieval_traces(self) -> None:
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

            report = run_benchmark(request, client=FakeBenchmarkAppClient(retrieval_payload=[]))

            self.assertEqual(report.sample_results[0].turn_id, "turn-1")
            self.assertEqual(report.sample_results[0].retrieval_trace_ids, ["trace-1"])
            self.assertEqual(report.sample_results[0].retrieval_trace_records, [])
            self.assertIsNone(report.sample_results[0].error)

    def test_run_benchmark_supports_legacy_dataset_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            fixture = Path(__file__).resolve().parents[1] / "fixtures" / "legacy" / "legacy_question_bank_sample.jsonl"
            legacy_dataset = root / "legacy_sample.jsonl"
            legacy_dataset.write_text(fixture.read_text(encoding="utf-8"), encoding="utf-8")

            request = RunBenchmarkRequest(
                package_dir=None,
                legacy_dataset=legacy_dataset,
                base_url="http://127.0.0.1:8081",
                provider="openai",
                build_id="build_123",
                user_id="bench-user",
                session_prefix="bench",
                timeout_seconds=180,
                output_root=root / "outputs",
            )

            report = run_benchmark(request, client=FakeBenchmarkAppClient())

            self.assertEqual(report.project_key, "legacy_dataset")
            self.assertEqual(report.suite_version, "legacy_import_v1")
            self.assertEqual(report.sample_count, 1)
            self.assertEqual(report.sample_results[0].question, "该接口的必填请求头有哪些？")
            self.assertEqual(report.sample_results[0].final_answer, "sample answer")

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
