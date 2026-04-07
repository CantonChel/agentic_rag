import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agentic_rag_benchmark.ragas_runner import build_ragas_rows
from agentic_rag_benchmark.ragas_runner import build_ragas_summary
from agentic_rag_benchmark.ragas_runner import evaluate_ragas_for_report
from agentic_rag_benchmark.ragas_runner import is_context_metric_eligible_row
from agentic_rag_benchmark.ragas_runner import read_first_non_empty_env
from agentic_rag_benchmark.ragas_runner import score_metric_rows
from agentic_rag_benchmark.runner import RunBenchmarkReport
from agentic_rag_benchmark.runner import RunBenchmarkRequest
from agentic_rag_benchmark.runner import RunBenchmarkSampleResult


class RagasRunnerTest(unittest.TestCase):
    def test_read_first_non_empty_env_prefers_first_defined_name(self) -> None:
        with patch.dict(os.environ, {"DEEPSEEK_MODEL": "deepseek-chat", "MINIMAX_MODEL": "MiniMax-M2.7"}, clear=True):
            value = read_first_non_empty_env("RAGAS_JUDGE_MODEL", "DEEPSEEK_MODEL", "MINIMAX_MODEL", default="fallback")
        self.assertEqual(value, "deepseek-chat")

    def test_read_first_non_empty_env_uses_default_when_all_empty(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            value = read_first_non_empty_env("RAGAS_JUDGE_MODEL", "DEEPSEEK_MODEL", default="deepseek-chat")
        self.assertEqual(value, "deepseek-chat")

    def test_build_ragas_rows_uses_context_output_as_context_source(self) -> None:
        sample = RunBenchmarkSampleResult(
            sample_id="sample_1",
            question="What is the answer?",
            ground_truth="gold answer",
            ground_truth_contexts=["gold context"],
            gold_block_refs=[],
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

    def test_evaluate_ragas_for_report_sanitizes_non_finite_metric_values_in_csv(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            report = self._build_report(root)

            artifacts = evaluate_ragas_for_report(
                report,
                root,
                evaluator=lambda rows: (
                    {
                        "record_count": 1,
                        "metrics": ["context_precision"],
                        "metrics_requested": ["faithfulness", "context_precision"],
                        "metrics_completed": ["context_precision"],
                        "metrics_failed": [{"metric": "faithfulness", "message": "Metric produced no finite scores."}],
                        "metric_row_stats": {
                            "faithfulness": {
                                "eligible_row_count": 1,
                                "scored_row_count": 0,
                                "recovered_row_count": 0,
                                "empty_or_nan_row_count": 1,
                            },
                            "context_precision": {
                                "eligible_row_count": 1,
                                "scored_row_count": 1,
                                "recovered_row_count": 0,
                                "empty_or_nan_row_count": 0,
                            },
                        },
                        "metric_row_diagnostics": {
                            "faithfulness": [
                                {
                                    "sample_id": "sample_1",
                                    "question": "What is the answer?",
                                    "initial_status": "non_finite_metric_value",
                                    "initial_message": "Batch evaluation returned a non-finite metric value: nan.",
                                    "status": "non_finite_metric_value",
                                    "message": "Single-row evaluation returned a non-finite metric value: nan.",
                                }
                            ],
                            "context_precision": [],
                        },
                        "warnings": ["faithfulness produced no finite scores."],
                        "scores": {"context_precision": 0.5},
                        "judge_provider": "fake",
                        "judge_model": "fake-model",
                        "judge_base_url": "http://fake",
                    },
                    [
                        {
                            "sample_id": "sample_1",
                            "question": "What is the answer?",
                            "answer": "predicted answer",
                            "ground_truth": "gold answer",
                            "contexts": ["final context A"],
                            "faithfulness": float("nan"),
                            "context_precision": 0.5,
                        }
                    ],
                ),
            )

            csv_text = artifacts.per_sample_csv_path.read_text(encoding="utf-8")
            self.assertIn("sample_id,question,answer,ground_truth,contexts,faithfulness,context_precision", csv_text)
            self.assertNotIn("nan", csv_text.lower())
            self.assertIn("sample_1,What is the answer?,predicted answer,gold answer,\"[\"\"final context A\"\"]\",,0.5", csv_text)

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

    def test_build_ragas_summary_ignores_non_finite_values_and_tracks_metric_failures(self) -> None:
        summary = build_ragas_summary(
            per_sample_rows=[
                {
                    "sample_id": "sample_1",
                    "question": "q1",
                    "answer": "a1",
                    "ground_truth": "g1",
                    "contexts": ["ctx"],
                    "faithfulness": float("nan"),
                    "context_precision": 0.5,
                    "context_recall": 1.0,
                },
                {
                    "sample_id": "sample_2",
                    "question": "q2",
                    "answer": "a2",
                    "ground_truth": "g2",
                    "contexts": [""],
                    "faithfulness": None,
                    "context_precision": None,
                    "context_recall": 0.0,
                },
            ],
            judge_model="fake-model",
            judge_base_url="http://fake",
            metrics_requested=["faithfulness", "context_precision", "context_recall"],
            metrics_completed=["context_precision", "context_recall"],
            metrics_failed=[{"metric": "faithfulness", "message": "Metric produced no finite scores."}],
            metric_row_stats={
                "faithfulness": {"eligible_row_count": 2, "scored_row_count": 0, "recovered_row_count": 0, "empty_or_nan_row_count": 2},
                "context_precision": {"eligible_row_count": 1, "scored_row_count": 1, "recovered_row_count": 0, "empty_or_nan_row_count": 0},
                "context_recall": {"eligible_row_count": 1, "scored_row_count": 1, "recovered_row_count": 0, "empty_or_nan_row_count": 0},
            },
            metric_row_diagnostics={
                "faithfulness": [
                    {
                        "sample_id": "sample_1",
                        "question": "q1",
                        "initial_status": "empty_metric_value",
                        "initial_message": "Batch evaluation returned an empty metric value.",
                        "status": "row_evaluation_error",
                        "message": "No statements were generated from the answer.",
                    }
                ]
            },
            warnings=["faithfulness produced no finite scores across 2 eligible rows."],
        )

        self.assertEqual(summary["metrics"], ["context_precision", "context_recall"])
        self.assertEqual(summary["scores"]["context_precision"], 0.5)
        self.assertEqual(summary["scores"]["context_recall"], 0.5)
        self.assertEqual(summary["metrics_failed"][0]["metric"], "faithfulness")
        self.assertEqual(summary["metric_row_stats"]["faithfulness"]["scored_row_count"], 0)
        self.assertEqual(summary["metric_row_stats"]["faithfulness"]["recovered_row_count"], 0)
        self.assertEqual(summary["metric_row_diagnostics"]["faithfulness"][0]["sample_id"], "sample_1")
        self.assertIn("faithfulness produced no finite scores", summary["warnings"][0])

    def test_is_context_metric_eligible_row_requires_ground_truth_and_non_empty_contexts(self) -> None:
        self.assertFalse(
            is_context_metric_eligible_row(
                {
                    "sample_id": "sample_1",
                    "ground_truth": "gold answer",
                    "contexts": [""],
                }
            )
        )
        self.assertFalse(
            is_context_metric_eligible_row(
                {
                    "sample_id": "sample_1",
                    "ground_truth": "",
                    "contexts": ["final context A"],
                }
            )
        )
        self.assertTrue(
            is_context_metric_eligible_row(
                {
                    "sample_id": "sample_1",
                    "ground_truth": "gold answer",
                    "contexts": ["final context A"],
                }
            )
        )

    def test_score_metric_rows_recovers_missing_scores_via_single_row_recheck(self) -> None:
        rows = [
            {
                "sample_id": "sample_1",
                "question": "What is the answer?",
                "answer": "predicted answer",
                "ground_truth": "gold answer",
                "contexts": ["ctx"],
            }
        ]

        call_count = {"value": 0}

        def fake_evaluate(dataset, metrics, llm):
            call_count["value"] += 1
            sample_id = list(dataset)[0]["sample_id"]
            if call_count["value"] == 1:
                return FakeEvaluateResult([{"sample_id": sample_id, "faithfulness": None}])
            return FakeEvaluateResult([{"sample_id": sample_id, "faithfulness": 0.7}])

        result = score_metric_rows(
            evaluate=fake_evaluate,
            dataset_factory=lambda items: list(items),
            metric_name="faithfulness",
            metric=object(),
            rows=rows,
            ragas_llm=object(),
            row_diagnostics_enabled=True,
            row_diagnostics_limit=20,
        )

        self.assertEqual(result.values_by_sample_id["sample_1"], 0.7)
        self.assertEqual(result.scored_row_count, 1)
        self.assertEqual(result.recovered_row_count, 1)
        self.assertEqual(result.diagnostics, [])

    def test_score_metric_rows_records_row_level_diagnostics_for_unresolved_rows(self) -> None:
        rows = [
            {
                "sample_id": "sample_1",
                "question": "What is the answer?",
                "answer": "predicted answer",
                "ground_truth": "gold answer",
                "contexts": ["ctx"],
            }
        ]

        call_count = {"value": 0}

        def fake_evaluate(dataset, metrics, llm):
            call_count["value"] += 1
            if call_count["value"] > 1:
                raise RuntimeError("No statements were generated from the answer.")
            return FakeEvaluateResult([{"sample_id": "sample_1", "faithfulness": None}])

        result = score_metric_rows(
            evaluate=fake_evaluate,
            dataset_factory=lambda items: list(items),
            metric_name="faithfulness",
            metric=object(),
            rows=rows,
            ragas_llm=object(),
            row_diagnostics_enabled=True,
            row_diagnostics_limit=20,
        )

        self.assertEqual(result.scored_row_count, 0)
        self.assertEqual(result.recovered_row_count, 0)
        self.assertEqual(result.diagnostics[0]["sample_id"], "sample_1")
        self.assertEqual(result.diagnostics[0]["initial_status"], "empty_metric_value")
        self.assertEqual(result.diagnostics[0]["status"], "row_evaluation_error")
        self.assertIn("No statements were generated from the answer.", result.diagnostics[0]["message"])

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
            gold_block_refs=[],
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
            gold_block_count=1,
            started_at="2026-03-28T00:00:00Z",
            completed_at="2026-03-28T00:00:10Z",
            sample_results=[sample],
        )


class FakeEvaluateResult:
    def __init__(self, rows):
        self._rows = rows

    def to_pandas(self):
        return FakeDataFrame(self._rows)


class FakeDataFrame:
    def __init__(self, rows):
        self._rows = rows

    def to_dict(self, orient="records"):
        if orient != "records":
            raise AssertionError(f"Unexpected orient: {orient}")
        return list(self._rows)


if __name__ == "__main__":
    unittest.main()
