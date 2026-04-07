import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from agentic_rag_benchmark.console_server import ConsoleConfig
from agentic_rag_benchmark.console_server import JobStore
from agentic_rag_benchmark.console_server import build_legacy_report_id
from agentic_rag_benchmark.console_server import build_legacy_run_history_record
from agentic_rag_benchmark.console_server import list_run_history
from agentic_rag_benchmark.console_server import create_app
from agentic_rag_benchmark.console_server import discover_report_output_dirs
from agentic_rag_benchmark.console_server import inspect_package_dir
from agentic_rag_benchmark.console_server import list_package_records
from agentic_rag_benchmark.console_server import load_run_report
from agentic_rag_benchmark.console_server import normalize_upload_relative_path
from agentic_rag_benchmark.runner_io import load_benchmark_package
from agentic_rag_benchmark.subset import create_random_subset_package
from agentic_rag_benchmark.tests.gold_package_test_utils import GoldPackageSpec
from agentic_rag_benchmark.tests.gold_package_test_utils import write_gold_package


class ConsoleServerTest(unittest.TestCase):
    def test_job_store_persists_job_state_to_disk(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            jobs_dir = Path(tmp_dir) / "jobs"
            store = JobStore(jobs_dir)
            job = store.create_job("run_benchmark", "queued", {"packagePath": "/tmp/pkg"}, status="queued")

            store.set_stage(
                job["jobId"],
                stage="running_samples",
                status="running",
                completed=3,
                total=10,
                message="Running sample 3",
                details={"sampleId": "sample_3"},
            )

            reloaded_store = JobStore(jobs_dir)
            reloaded_job = reloaded_store.get_job(job["jobId"])

            self.assertEqual(reloaded_job["status"], "running")
            self.assertEqual(reloaded_job["stage"], "running_samples")
            self.assertEqual(reloaded_job["progress"]["completed"], 3)
            self.assertEqual(reloaded_job["progress"]["total"], 10)
            self.assertEqual(reloaded_job["progress"]["percent"], 30.0)
            self.assertEqual(reloaded_job["logs"][-1]["message"], "Running sample 3")

    def test_list_run_history_returns_latest_completed_reports(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            jobs_dir = Path(tmp_dir) / "jobs"
            store = JobStore(jobs_dir)
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=Path(tmp_dir) / "benchmark_root",
                work_root=Path(tmp_dir) / "work_root",
            )
            older = store.create_job("run_benchmark", "running_samples", {"provider": "minimax"}, status="running")
            store.complete_job(
                older["jobId"],
                result={
                    "projectKey": "api_docs",
                    "suiteVersion": "base_v1",
                    "sampleCount": 10,
                    "buildId": "build_old",
                    "provider": "minimax",
                    "summary": {"execution_success_count": 10, "execution_success_rate": 100.0},
                    "ragasSummary": {"scores": {"faithfulness": 0.82}, "metrics_completed": ["faithfulness"]},
                },
                artifacts={"outputDir": "/tmp/benchmark-console/outputs/old"},
            )
            time.sleep(0.01)
            newer = store.create_job("run_benchmark", "running_samples", {"provider": "deepseek"}, status="running")
            store.complete_job(
                newer["jobId"],
                result={
                    "projectKey": "apifox_docs",
                    "suiteVersion": "smoke_10",
                    "sampleCount": 10,
                    "buildId": "build_new",
                    "provider": "deepseek",
                    "summary": {"execution_success_count": 9, "execution_success_rate": 90.0},
                    "ragasSummary": {"scores": {"faithfulness": 0.91}, "metrics_completed": ["faithfulness"]},
                },
                artifacts={"outputDir": "/tmp/benchmark-console/outputs/new"},
            )

            history = list_run_history(job_store=store, config=config)

            self.assertEqual(len(history), 2)
            self.assertEqual(history[0]["buildId"], "build_new")
            self.assertEqual(history[0]["projectKey"], "apifox_docs")
            self.assertEqual(history[0]["executionSuccessRate"], 90.0)
            self.assertEqual(history[0]["ragasScores"]["faithfulness"], 0.91)
            self.assertTrue(history[0]["reportAvailable"])

    def test_list_run_history_includes_legacy_output_directories(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            legacy_output_dir = benchmark_root / "outputs" / "run_20260331_135223"
            legacy_output_dir.mkdir(parents=True, exist_ok=True)
            self._write_report_output(
                output_dir=legacy_output_dir,
                project_key="ragas_smoke",
                suite_version="smoke_v1_10",
                build_id="build_legacy",
                provider="minimax",
                sample_count=10,
                execution_success_rate=100.0,
                faithfulness=0.72,
            )
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )
            store = JobStore(config.jobs_dir)

            output_dirs = discover_report_output_dirs(config)
            history = list_run_history(job_store=store, config=config)

            self.assertEqual([item.resolve() for item in output_dirs], [legacy_output_dir.resolve()])
            self.assertEqual(len(history), 1)
            self.assertEqual(history[0]["reportSource"], "legacy_output")
            self.assertEqual(history[0]["buildId"], "build_legacy")
            self.assertEqual(history[0]["ragasScores"]["faithfulness"], 0.72)
            self.assertEqual(history[0]["jobId"], build_legacy_report_id(legacy_output_dir))

    def test_build_legacy_run_history_record_reads_old_output_dir(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_dir = Path(tmp_dir) / "outputs" / "run_20260331_135852"
            output_dir.mkdir(parents=True, exist_ok=True)
            self._write_report_output(
                output_dir=output_dir,
                project_key="ragas_smoke",
                suite_version="smoke_v1_10",
                build_id="build_legacy",
                provider="deepseek",
                sample_count=10,
                execution_success_rate=90.0,
                faithfulness=0.81,
            )

            record = build_legacy_run_history_record(output_dir)

            self.assertEqual(record["reportSource"], "legacy_output")
            self.assertEqual(record["provider"], "deepseek")
            self.assertEqual(record["sampleCount"], 10)
            self.assertEqual(record["executionSuccessRate"], 90.0)
            self.assertEqual(record["ragasScores"]["faithfulness"], 0.81)

    def test_list_and_inspect_package_records_expose_manifest_and_previews(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            package_dir = self._write_package(benchmark_root, "api_docs", "base_v1")
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )

            packages = list_package_records(config)
            inspection = inspect_package_dir(package_dir, preview_limit=1)

            self.assertEqual(len(packages), 1)
            self.assertEqual(packages[0]["projectKey"], "api_docs")
            self.assertEqual(packages[0]["suiteVersion"], "base_v1")
            self.assertEqual(packages[0]["sampleCount"], 3)
            self.assertEqual(packages[0]["goldBlockCount"], 3)
            self.assertEqual(inspection["sampleCount"], 3)
            self.assertEqual(inspection["goldBlockCount"], 3)
            self.assertEqual(len(inspection["samplePreview"]), 1)
            self.assertEqual(len(inspection["authoringBlockPreview"]), 1)
            self.assertTrue(inspection["validation"]["ok"])

    def test_random_subset_package_keeps_sample_evidence_closure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            package_dir = self._write_package(benchmark_root, "api_docs", "base_v1")
            subset = create_random_subset_package(
                package_dir=package_dir,
                sample_count=2,
                seed=42,
                output_root=root / "subsets",
                suite_version_suffix="smoke_2",
            )

            loaded_subset = load_benchmark_package(subset.package_dir)
            selected_block_ids = {
                ref.block_id
                for sample in loaded_subset.benchmark_samples
                for ref in sample.gold_block_refs
            }
            subset_block_ids = {item.block_id for item in loaded_subset.authoring_blocks}

            self.assertEqual(len(loaded_subset.benchmark_samples), 2)
            self.assertEqual(subset.selected_sample_count, 2)
            self.assertEqual(selected_block_ids, subset_block_ids)
            self.assertEqual(subset.selected_gold_block_count, len(subset_block_ids))

    def test_load_run_report_merges_summary_and_ragas_rows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_dir = Path(tmp_dir) / "run_20260331_000000"
            output_dir.mkdir(parents=True)
            self._write_report_output(
                output_dir=output_dir,
                project_key="api_docs",
                suite_version="base_v1",
                build_id="build_123",
                provider="minimax",
                sample_count=1,
                execution_success_rate=100.0,
                faithfulness=1.0,
                context_precision=0.75,
            )

            report = load_run_report(output_dir)

            self.assertEqual(report["summary"]["execution_success_count"], 1)
            self.assertEqual(report["samples"][0]["sampleId"], "sample_1")
            self.assertEqual(report["samples"][0]["contextOutputChunkCount"], 1)
            self.assertEqual(report["samples"][0]["ragasScores"]["faithfulness"], 1)
            self.assertEqual(report["samples"][0]["ragasScores"]["context_precision"], 0.75)
            self.assertIsNone(report["samples"][0]["ragasScores"].get("context_recall"))

    def test_normalize_upload_relative_path_rejects_parent_segments(self) -> None:
        self.assertEqual(
            normalize_upload_relative_path("docs/reference/api.pdf", "api.pdf").as_posix(),
            "docs/reference/api.pdf",
        )
        with self.assertRaises(ValueError):
            normalize_upload_relative_path("../secret.txt", "secret.txt")

    def test_package_endpoints_return_expected_payloads(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            package_dir = self._write_package(benchmark_root, "api_docs", "base_v1")
            (benchmark_root / "console_static").mkdir(parents=True, exist_ok=True)
            (benchmark_root / "console_static" / "benchmark.html").write_text("<html>ok</html>", encoding="utf-8")
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )
            client = TestClient(create_app(config))

            packages_response = client.get("/api/packages")
            inspect_response = client.get("/api/packages/inspect", params={"packagePath": str(package_dir)})

            self.assertEqual(packages_response.status_code, 200)
            self.assertEqual(inspect_response.status_code, 200)
            self.assertEqual(packages_response.json()["packages"][0]["projectKey"], "api_docs")
            self.assertEqual(inspect_response.json()["sampleCount"], 3)

    def test_runs_endpoint_returns_historical_run_records(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            (benchmark_root / "console_static").mkdir(parents=True, exist_ok=True)
            (benchmark_root / "console_static" / "benchmark.html").write_text("<html>ok</html>", encoding="utf-8")
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )
            store = JobStore(config.jobs_dir)
            job = store.create_job("run_benchmark", "running_samples", {"provider": "minimax"}, status="running")
            store.complete_job(
                job["jobId"],
                result={
                    "projectKey": "api_docs",
                    "suiteVersion": "base_v1",
                    "sampleCount": 5,
                    "buildId": "build_123",
                    "provider": "minimax",
                    "summary": {"execution_success_count": 5, "execution_success_rate": 100.0},
                    "ragasSummary": {"scores": {"faithfulness": 0.88}, "metrics_completed": ["faithfulness"]},
                },
                artifacts={"outputDir": "/tmp/benchmark-console/outputs/job1"},
            )

            client = TestClient(create_app(config))
            response = client.get("/api/runs")

            self.assertEqual(response.status_code, 200)
            payload = response.json()
            self.assertEqual(len(payload["runs"]), 1)
            self.assertEqual(payload["runs"][0]["buildId"], "build_123")
            self.assertEqual(payload["runs"][0]["sampleCount"], 5)
            self.assertEqual(payload["runs"][0]["ragasScores"]["faithfulness"], 0.88)

    def test_legacy_report_can_be_opened_via_runs_report_endpoint(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            output_dir = benchmark_root / "outputs" / "run_20260331_135852"
            output_dir.mkdir(parents=True, exist_ok=True)
            self._write_report_output(
                output_dir=output_dir,
                project_key="ragas_smoke",
                suite_version="smoke_v1_10",
                build_id="build_legacy",
                provider="minimax",
                sample_count=10,
                execution_success_rate=100.0,
                faithfulness=0.72,
            )
            (benchmark_root / "console_static").mkdir(parents=True, exist_ok=True)
            (benchmark_root / "console_static" / "benchmark.html").write_text("<html>ok</html>", encoding="utf-8")
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )
            client = TestClient(create_app(config))

            response = client.get(f"/api/runs/{build_legacy_report_id(output_dir)}/report")

            self.assertEqual(response.status_code, 200)
            payload = response.json()
            self.assertEqual(payload["runMeta"]["build_id"], "build_legacy")
            self.assertEqual(payload["summary"]["execution_success_rate"], 100.0)
            self.assertEqual(payload["ragasSummary"]["scores"]["faithfulness"], 0.72)

    def test_build_upload_endpoint_creates_job_and_stages_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "benchmark_root"
            work_root = root / "work_root"
            (benchmark_root / "console_static").mkdir(parents=True, exist_ok=True)
            (benchmark_root / "console_static" / "benchmark.html").write_text("<html>ok</html>", encoding="utf-8")
            config = ConsoleConfig(
                host="127.0.0.1",
                port=8092,
                app_base_url="http://127.0.0.1:8081",
                docreader_base_url="http://127.0.0.1:8090",
                benchmark_root=benchmark_root,
                work_root=work_root,
            )
            client = TestClient(create_app(config))

            with patch("agentic_rag_benchmark.console_server.start_background_job") as mocked_background_job:
                response = client.post(
                    "/api/packages/build-upload",
                    data={
                        "projectKey": "api_docs",
                        "suiteVersion": "base_v1",
                        "relative_paths": "docs/reference.md",
                    },
                    files=[
                        (
                            "files",
                            ("reference.md", b"# heading\ncontent\n", "text/markdown"),
                        )
                    ],
                )

            self.assertEqual(response.status_code, 200)
            payload = response.json()
            self.assertIn("jobId", payload)
            mocked_background_job.assert_called_once()
            upload_path = work_root / "uploads" / payload["jobId"] / "docs" / "reference.md"
            self.assertTrue(upload_path.exists())
            self.assertIn("content", upload_path.read_text(encoding="utf-8"))

    def _write_package(self, benchmark_root: Path, project_key: str, suite_version: str) -> Path:
        package_dir = benchmark_root / "packages" / project_key / suite_version
        write_gold_package(
            package_dir=package_dir,
            project_key=project_key,
            suite_version=suite_version,
            generator_version="test_console",
            specs=[
                GoldPackageSpec(
                    block_id="block-1",
                    doc_path="docs/intro.md",
                    section_key="overview",
                    section_title="Overview",
                    text="sample context 1",
                    question="What is sample context 1?",
                    ground_truth="sample answer 1",
                    tags=["intro"],
                    difficulty="easy",
                ),
                GoldPackageSpec(
                    block_id="block-2",
                    doc_path="docs/auth.md",
                    section_key="headers",
                    section_title="Headers",
                    text="sample context 2",
                    question="What header is required?",
                    ground_truth="sample answer 2",
                    tags=["auth"],
                    difficulty="medium",
                ),
                GoldPackageSpec(
                    block_id="block-3",
                    doc_path="docs/errors.md",
                    section_key="errors",
                    section_title="Errors",
                    text="sample context 3",
                    question="What does the error mean?",
                    ground_truth="sample answer 3",
                    tags=["errors"],
                    difficulty="medium",
                ),
            ],
        )
        return package_dir

    def _write_report_output(
        self,
        output_dir: Path,
        project_key: str,
        suite_version: str,
        build_id: str,
        provider: str,
        sample_count: int,
        execution_success_rate: float,
        faithfulness: float,
        context_precision: float | None = None,
    ) -> None:
        output_dir.mkdir(parents=True, exist_ok=True)
        execution_success_count = int(round((execution_success_rate / 100.0) * sample_count))
        summary = {
            "sample_count": sample_count,
            "execution_success_count": execution_success_count,
            "execution_success_rate": execution_success_rate,
            "retrieval_hit_overview": {
                "samples_with_context_output": sample_count,
                "samples_without_context_output": 0,
                "context_output_chunk_count": sample_count,
            },
        }
        (output_dir / "run_meta.json").write_text(
            json.dumps(
                {
                    "package_dir": f"/tmp/packages/{project_key}/{suite_version}",
                    "build_id": build_id,
                    "project_key": project_key,
                    "suite_version": suite_version,
                    "provider": provider,
                    "sample_count": sample_count,
                    "started_at": "2026-03-31T05:43:19Z",
                    "completed_at": "2026-03-31T05:46:39Z",
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        (output_dir / "benchmark_report.json").write_text(
            json.dumps(
                {
                    "run_meta": {
                        "package_dir": f"/tmp/packages/{project_key}/{suite_version}",
                        "build_id": build_id,
                        "project_key": project_key,
                        "suite_version": suite_version,
                        "provider": provider,
                        "sample_count": sample_count,
                    },
                    "summary": summary,
                    "samples": [
                        {
                            "sample_id": "sample_1",
                            "question": "What is sample context?",
                            "ground_truth": "sample answer",
                            "final_answer": "predicted answer",
                            "finish_reason": "stop",
                            "latency_ms": 88,
                            "gold_block_refs": [{"block_id": "block-1"}],
                            "ground_truth_contexts": ["sample context"],
                            "retrieval_trace_records": [
                                {"stage": "context_output", "recordType": "chunk", "chunkText": "sample context"}
                            ],
                        }
                    ],
                    "failed_samples": [],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        ragas_scores = {"faithfulness": faithfulness}
        if context_precision is not None:
            ragas_scores["context_precision"] = context_precision
        (output_dir / "ragas_summary.json").write_text(
            json.dumps(
                {
                    "metrics_completed": list(ragas_scores.keys()),
                    "scores": ragas_scores,
                    "warnings": [],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        csv_fields = ["sample_id", "faithfulness"]
        csv_values = ["sample_1", str(faithfulness)]
        if context_precision is not None:
            csv_fields.append("context_precision")
            csv_values.append(str(context_precision))
        (output_dir / "ragas_scores_per_sample.csv").write_text(
            ",".join(csv_fields) + "\n" + ",".join(csv_values) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
