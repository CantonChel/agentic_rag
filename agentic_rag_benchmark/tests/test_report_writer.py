import json
import tempfile
import unittest
from pathlib import Path
from typing import Any
from typing import Optional

from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.report_writer import write_benchmark_outputs
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import run_benchmark
from agentic_rag_benchmark.runner_client import AgentStreamCapture


class FakeBenchmarkAppClient:
    def __init__(
        self,
        stream_capture: Optional[AgentStreamCapture] = None,
        summary_payload: Optional[dict[str, Any]] = None,
        retrieval_payload: Optional[list[dict[str, Any]]] = None,
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
            {"traceId": "trace-1", "stage": "context_output", "recordType": "chunk", "chunkId": "e1"}
        ]

    def stream_agent_turn(self, **_: Any) -> AgentStreamCapture:
        return self.stream_capture

    def get_turn_summary(self, turn_id: str) -> dict[str, Any]:
        payload = dict(self.summary_payload)
        payload["turnId"] = payload.get("turnId") or turn_id
        return payload

    def get_retrieval_traces(self, trace_id: str) -> list[dict[str, Any]]:
        return [dict(item, traceId=item.get("traceId", trace_id)) for item in self.retrieval_payload]


class ReportWriterTest(unittest.TestCase):
    def test_write_benchmark_outputs_generates_stage6_artifacts(self) -> None:
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
            artifacts = write_benchmark_outputs(report)

            self.assertTrue(artifacts.run_meta_path.exists())
            self.assertTrue(artifacts.samples_collected_path.exists())
            self.assertTrue(artifacts.benchmark_report_json_path.exists())
            self.assertTrue(artifacts.benchmark_report_markdown_path.exists())

            run_meta = json.loads(artifacts.run_meta_path.read_text(encoding="utf-8"))
            self.assertEqual(run_meta["project_key"], "api_docs")
            self.assertEqual(run_meta["build_id"], "build_123")

            report_json = json.loads(artifacts.benchmark_report_json_path.read_text(encoding="utf-8"))
            self.assertEqual(report_json["summary"]["success_count"], 1)
            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["context_output_chunk_count"], 1)
            self.assertEqual(len(report_json["samples"]), 1)

            report_markdown = artifacts.benchmark_report_markdown_path.read_text(encoding="utf-8")
            self.assertIn("## 运行摘要", report_markdown)
            self.assertIn("## 总体成功率", report_markdown)
            self.assertIn("## 平均耗时", report_markdown)
            self.assertIn("## finish reason 分布", report_markdown)
            self.assertIn("## 失败样本列表", report_markdown)
            self.assertIn("## 检索命中概览", report_markdown)

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
