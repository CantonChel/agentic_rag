import json
import tempfile
import unittest
from pathlib import Path
from typing import Any
from typing import Optional

from agentic_rag_benchmark.report_writer import write_benchmark_outputs
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import run_benchmark
from agentic_rag_benchmark.runner_client import AgentStreamCapture
from agentic_rag_benchmark.tests.gold_package_test_utils import GoldPackageSpec
from agentic_rag_benchmark.tests.gold_package_test_utils import write_gold_package


class FakeBenchmarkAppClient:
    def __init__(
        self,
        stream_capture: Optional[AgentStreamCapture] = None,
        summary_payload: Optional[dict[str, Any]] = None,
        retrieval_payload: Optional[list[dict[str, Any]]] = None,
        chunk_mapping_payload: Optional[dict[str, list[dict[str, Any]]]] = None,
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
            {"traceId": "trace-1", "stage": "context_output", "recordType": "chunk", "chunkId": "chunk-1"}
        ]
        self.chunk_mapping_payload = chunk_mapping_payload if chunk_mapping_payload is not None else {
            "chunk-1": [{"chunkId": "chunk-1", "goldBlockIds": ["block-1"], "primaryGoldBlockId": "block-1"}]
        }

    def stream_agent_turn(self, **_: Any) -> AgentStreamCapture:
        return self.stream_capture

    def get_turn_summary(self, turn_id: str) -> dict[str, Any]:
        payload = dict(self.summary_payload)
        payload["turnId"] = payload.get("turnId") or turn_id
        return payload

    def get_retrieval_traces(self, trace_id: str) -> list[dict[str, Any]]:
        return [dict(item, traceId=item.get("traceId", trace_id)) for item in self.retrieval_payload]

    def get_build_chunk_mappings(self, build_id: str, chunk_id: str | None = None) -> list[dict[str, Any]]:
        if chunk_id is None:
            payload: list[dict[str, Any]] = []
            for rows in self.chunk_mapping_payload.values():
                payload.extend(rows)
            return payload
        return [dict(item, buildId=build_id) for item in self.chunk_mapping_payload.get(chunk_id, [])]


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
            self.assertEqual(run_meta["gold_block_count"], 1)

            report_json = json.loads(artifacts.benchmark_report_json_path.read_text(encoding="utf-8"))
            self.assertEqual(report_json["summary"]["success_count"], 1)
            self.assertEqual(report_json["summary"]["execution_success_count"], 1)
            self.assertEqual(report_json["summary"]["execution_success_rate"], 100.0)
            self.assertEqual(
                report_json["summary"]["success_definition"],
                "Sample has no runner error, has a turn_id, and produced a non-empty final_answer.",
            )
            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["context_output_chunk_count"], 1)
            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["samples_with_context_output"], 1)
            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["samples_without_context_output"], 0)
            self.assertEqual(len(report_json["samples"]), 1)
            self.assertEqual(report_json["samples"][0]["gold_block_refs"][0]["block_id"], "block-1")
            self.assertEqual(report_json["samples"][0]["retrieved_chunk_ids"], ["chunk-1"])
            self.assertEqual(report_json["samples"][0]["retrieved_gold_block_ids_any_stage"], ["block-1"])
            self.assertEqual(report_json["samples"][0]["retrieved_gold_block_ids_context_output"], ["block-1"])
            self.assertTrue(report_json["samples"][0]["target_gold_block_hit_any_stage"])
            self.assertTrue(report_json["samples"][0]["target_gold_block_hit_context_output"])

            report_markdown = artifacts.benchmark_report_markdown_path.read_text(encoding="utf-8")
            self.assertIn("## 运行摘要", report_markdown)
            self.assertIn("## 执行成功率", report_markdown)
            self.assertIn("runner 成功完成该样本，不代表答案正确率", report_markdown)
            self.assertIn("## 平均耗时", report_markdown)
            self.assertIn("## finish reason 分布", report_markdown)
            self.assertIn("## 失败样本列表", report_markdown)
            self.assertIn("## 检索命中概览", report_markdown)
            self.assertIn("gold_block_count: `1`", report_markdown)

    def test_write_benchmark_outputs_tracks_samples_without_context_output(self) -> None:
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
            artifacts = write_benchmark_outputs(report)
            report_json = json.loads(artifacts.benchmark_report_json_path.read_text(encoding="utf-8"))

            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["samples_with_context_output"], 0)
            self.assertEqual(report_json["summary"]["retrieval_hit_overview"]["samples_without_context_output"], 1)
            report_markdown = artifacts.benchmark_report_markdown_path.read_text(encoding="utf-8")
            self.assertIn("samples_without_context_output: `1` / `1`", report_markdown)

    def _write_package(self, package_dir: Path) -> None:
        write_gold_package(
            package_dir=package_dir,
            project_key="api_docs",
            suite_version="base_v1",
            specs=[
                GoldPackageSpec(
                    block_id="block-1",
                    doc_path="docs/sample.md",
                    section_key="overview",
                    section_title="Overview",
                    text="sample context",
                    question="What is sample context?",
                    ground_truth="sample answer",
                    tags=["api"],
                    difficulty="easy",
                )
            ],
        )


if __name__ == "__main__":
    unittest.main()
