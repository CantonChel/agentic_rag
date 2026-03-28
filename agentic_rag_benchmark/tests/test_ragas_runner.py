import json
import tempfile
import unittest
from pathlib import Path

from agentic_rag_benchmark.ragas_runner import build_ragas_rows
from agentic_rag_benchmark.ragas_runner import evaluate_ragas_for_report
from agentic_rag_benchmark.runner import RunBenchmarkReport
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import RunBenchmarkSampleResult


class RagasRunnerTest(unittest.TestCase):
    def test_build_ragas_rows_uses_context_output_as_context_source(self) -> None:
        sample = RunBenchmarkSampleResult(
            sample_id="sample_1",
            question="What is the answer?",
            ground_truth="gold answer",
            ground_truth_contexts=["gold context"],
            gold_evidence_refs=[],
            provider="openai",
            build_id="build_123",
            session_id="session_1",
            turn_id="turn_1",
            trace_id="trace_1",
            final_answer="predicted answer",
            finish_reason="stop",
            retrieval_trace_records=[
                {"stage": "dense", "recordType": "chunk", "chunkText": "dense only"},
                {"stage": "context_output", "recordType": "summary", "chunkText": "skip summary"},
                {"stage": "context_output", "recordType": "chunk", "chunkText": "final context A"},
                {"stage": "context_output", "recordType": "chunk", "chunkText": "final context B"},
            ],
        )

        rows = build_ragas_rows([sample])

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["question"], "What is the answer?")
        self.assertEqual(rows[0]["answer"], "predicted answer")
        self.assertEqual(rows[0]["ground_truth"], "gold answer")
        self.assertEqual(rows[0]["contexts"], ["final context A", "final context B"])

    def test_evaluate_ragas_for_report_writes_summary_and_csv(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            report = self._build_report(root)

            def fake_evaluator(rows):
                self.assertEqual(rows[0]["contexts"], ["final context A"])
                return (
                    {
                        "record_count": 1,
                        "metrics": ["faithfulness"],
                        "scores": {"faithfulness": 0.9},
                        "judge_provider": "fake",
                        "judge_model": "fake-model",
                        "judge_base_url": "http://fake",
                    },
                    [
                        {
                            "sample_id": "sample_1",
                            "faithfulness": 0.9,
                        }
                    ],
                )

            artifacts = evaluate_ragas_for_report(report, root, evaluator=fake_evaluator)

            self.assertIsNotNone(artifacts.summary_path)
            self.assertIsNotNone(artifacts.per_sample_csv_path)
            self.assertIsNone(artifacts.error_path)
            summary = json.loads(artifacts.summary_path.read_text(encoding="utf-8"))
            self.assertEqual(summary["scores"]["faithfulness"], 0.9)
            csv_text = artifacts.per_sample_csv_path.read_text(encoding="utf-8")
            self.assertIn("sample_id,faithfulness", csv_text)
            self.assertIn("sample_1,0.9", csv_text)

    def test_evaluate_ragas_for_report_writes_error_payload_when_evaluation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            report = self._build_report(root)

            artifacts = evaluate_ragas_for_report(
                report,
                root,
                evaluator=lambda rows: (_ for _ in ()).throw(RuntimeError("ragas failed")),
            )

            self.assertIsNone(artifacts.summary_path)
            self.assertIsNotNone(artifacts.error_path)
            error_payload = json.loads(artifacts.error_path.read_text(encoding="utf-8"))
            self.assertIn("ragas failed", error_payload["message"])
            self.assertEqual(error_payload["input_row_count"], 1)

    def _build_report(self, root: Path) -> RunBenchmarkReport:
        request = RunBenchmarkRequest(
            package_dir=root / "packages" / "api_docs" / "base_v1",
            base_url="http://127.0.0.1:8081",
            provider="openai",
            build_id="build_123",
            user_id="bench-user",
            session_prefix="bench",
            timeout_seconds=180,
            output_root=root / "outputs",
        )
        sample = RunBenchmarkSampleResult(
            sample_id="sample_1",
            question="What is the answer?",
            ground_truth="gold answer",
            ground_truth_contexts=["gold context"],
            gold_evidence_refs=[],
            provider="openai",
            build_id="build_123",
            session_id="session_1",
            turn_id="turn_1",
            trace_id="trace_1",
            final_answer="predicted answer",
            finish_reason="stop",
            retrieval_trace_records=[
                {"stage": "context_output", "recordType": "chunk", "chunkText": "final context A"},
            ],
        )
        return RunBenchmarkReport(
            request=request,
            project_key="api_docs",
            suite_version="base_v1",
            sample_count=1,
            evidence_count=1,
            started_at="2026-03-28T00:00:00Z",
            completed_at="2026-03-28T00:00:10Z",
            sample_results=[sample],
        )


if __name__ == "__main__":
    unittest.main()
