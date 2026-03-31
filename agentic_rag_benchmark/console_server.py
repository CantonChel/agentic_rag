"""Independent Python benchmark console service."""

from __future__ import annotations

import csv
import inspect
import json
import math
import os
import shutil
import threading
import time
from dataclasses import asdict
from dataclasses import dataclass
from datetime import datetime
from datetime import timezone
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any
from typing import Callable
from typing import Dict
from typing import List
from uuid import uuid4

import requests
from fastapi import FastAPI
from fastapi import HTTPException
from fastapi import Request
from fastapi.responses import FileResponse

from .package_spec import STANDARD_PACKAGE_FILES
from .pipeline import build_benchmark_package
from .progress import ProgressEvent
from .ragas_runner import evaluate_ragas_for_report
from .report_writer import count_context_output_hits
from .report_writer import write_benchmark_outputs
from .runner import RunBenchmarkRequest
from .runner import run_benchmark
from .runner_io import load_benchmark_package
from .subset import SubsetPackageResult
from .subset import create_random_subset_package
from .validator import ValidationResult
from .validator import validate_package_dir


DEFAULT_CONSOLE_HOST = "127.0.0.1"
DEFAULT_CONSOLE_PORT = 8092
DEFAULT_APP_BASE_URL = "http://127.0.0.1:8081"
DEFAULT_DOCREADER_BASE_URL = "http://127.0.0.1:8090"
DEFAULT_WORK_ROOT = Path("/tmp/benchmark-console")
DEFAULT_PACKAGE_PREVIEW_LIMIT = 5
DEFAULT_BUILD_POLL_INTERVAL_SECONDS = 2.0
DEFAULT_BUILD_READY_TIMEOUT_SECONDS = 600
MAX_JOB_LOGS = 500
JOB_STAGE_COMPLETED = "completed"
JOB_STAGE_FAILED = "failed"
RAGAS_CSV_BASE_FIELDS = {"sample_id", "question", "answer", "ground_truth", "contexts"}


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class ConsoleConfig:
    host: str
    port: int
    app_base_url: str
    docreader_base_url: str
    benchmark_root: Path
    work_root: Path
    package_preview_limit: int = DEFAULT_PACKAGE_PREVIEW_LIMIT
    build_poll_interval_seconds: float = DEFAULT_BUILD_POLL_INTERVAL_SECONDS
    build_ready_timeout_seconds: int = DEFAULT_BUILD_READY_TIMEOUT_SECONDS

    @property
    def packages_dir(self) -> Path:
        return self.benchmark_root / "packages"

    @property
    def jobs_dir(self) -> Path:
        return self.work_root / "jobs"

    @property
    def uploads_dir(self) -> Path:
        return self.work_root / "uploads"

    @property
    def subpackages_dir(self) -> Path:
        return self.work_root / "subpackages"

    @property
    def outputs_dir(self) -> Path:
        return self.work_root / "outputs"

    @property
    def static_html_path(self) -> Path:
        return self.benchmark_root / "console_static" / "benchmark.html"

    @classmethod
    def from_env(cls) -> "ConsoleConfig":
        benchmark_root = Path(
            os.getenv("BENCHMARK_CONSOLE_PACKAGE_ROOT", str(Path(__file__).resolve().parent))
        ).expanduser()
        work_root = Path(os.getenv("BENCHMARK_CONSOLE_WORK_ROOT", str(DEFAULT_WORK_ROOT))).expanduser()
        preview_limit = read_non_negative_int_env("BENCHMARK_CONSOLE_PACKAGE_PREVIEW_LIMIT", DEFAULT_PACKAGE_PREVIEW_LIMIT)
        port = read_non_negative_int_env("BENCHMARK_CONSOLE_PORT", DEFAULT_CONSOLE_PORT)
        return cls(
            host=str(os.getenv("BENCHMARK_CONSOLE_HOST", DEFAULT_CONSOLE_HOST)).strip() or DEFAULT_CONSOLE_HOST,
            port=port or DEFAULT_CONSOLE_PORT,
            app_base_url=str(os.getenv("BENCHMARK_CONSOLE_APP_BASE_URL", DEFAULT_APP_BASE_URL)).strip() or DEFAULT_APP_BASE_URL,
            docreader_base_url=(
                str(os.getenv("BENCHMARK_CONSOLE_DOCREADER_BASE_URL", DEFAULT_DOCREADER_BASE_URL)).strip()
                or DEFAULT_DOCREADER_BASE_URL
            ),
            benchmark_root=benchmark_root.resolve(),
            work_root=work_root.resolve(),
            package_preview_limit=preview_limit or DEFAULT_PACKAGE_PREVIEW_LIMIT,
            build_poll_interval_seconds=float(
                os.getenv("BENCHMARK_CONSOLE_BUILD_POLL_INTERVAL_SECONDS", DEFAULT_BUILD_POLL_INTERVAL_SECONDS)
            ),
            build_ready_timeout_seconds=read_non_negative_int_env(
                "BENCHMARK_CONSOLE_BUILD_READY_TIMEOUT_SECONDS",
                DEFAULT_BUILD_READY_TIMEOUT_SECONDS,
            )
            or DEFAULT_BUILD_READY_TIMEOUT_SECONDS,
        )


class JobStore:
    """File-backed job status store for the console."""

    def __init__(self, jobs_dir: Path) -> None:
        self.jobs_dir = jobs_dir
        self.jobs_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    def create_job(
        self,
        job_type: str,
        stage: str,
        input_payload: Dict[str, Any] | None = None,
        status: str = "queued",
    ) -> Dict[str, Any]:
        job_id = uuid4().hex
        now = utcnow_iso()
        payload = {
            "jobId": job_id,
            "jobType": job_type,
            "status": status,
            "stage": stage,
            "createdAt": now,
            "updatedAt": now,
            "progress": {
                "completed": None,
                "total": None,
                "percent": None,
                "message": "",
                "details": {},
            },
            "input": dict(input_payload or {}),
            "result": {},
            "artifacts": {},
            "error": None,
            "logs": [],
        }
        with self._lock:
            self._write_job(payload)
        return payload

    def get_job(self, job_id: str) -> Dict[str, Any]:
        with self._lock:
            return self._read_job(job_id)

    def set_stage(
        self,
        job_id: str,
        *,
        stage: str,
        status: str | None = None,
        completed: int | None = None,
        total: int | None = None,
        message: str = "",
        details: Dict[str, Any] | None = None,
        append_log: bool = True,
    ) -> Dict[str, Any]:
        def mutate(job: Dict[str, Any]) -> None:
            if status is not None:
                job["status"] = status
            job["stage"] = stage
            progress = dict(job.get("progress") or {})
            progress["completed"] = completed
            progress["total"] = total
            progress["percent"] = compute_percent(completed, total)
            progress["message"] = message
            progress["details"] = dict(details or {})
            job["progress"] = progress
            if append_log and message:
                logs = list(job.get("logs") or [])
                logs.append(
                    {
                        "time": utcnow_iso(),
                        "stage": stage,
                        "message": message,
                        "details": dict(details or {}),
                    }
                )
                job["logs"] = logs[-MAX_JOB_LOGS:]

        return self._mutate(job_id, mutate)

    def merge_result(self, job_id: str, result: Dict[str, Any]) -> Dict[str, Any]:
        def mutate(job: Dict[str, Any]) -> None:
            merged = dict(job.get("result") or {})
            merged.update(result)
            job["result"] = merged

        return self._mutate(job_id, mutate)

    def complete_job(
        self,
        job_id: str,
        *,
        result: Dict[str, Any] | None = None,
        artifacts: Dict[str, Any] | None = None,
        message: str = "Job completed",
    ) -> Dict[str, Any]:
        def mutate(job: Dict[str, Any]) -> None:
            job["status"] = "completed"
            job["stage"] = JOB_STAGE_COMPLETED
            progress = dict(job.get("progress") or {})
            progress["completed"] = 1
            progress["total"] = 1
            progress["percent"] = 100.0
            progress["message"] = message
            progress["details"] = {}
            job["progress"] = progress
            if result is not None:
                job["result"] = dict(result)
            if artifacts is not None:
                job["artifacts"] = dict(artifacts)
            logs = list(job.get("logs") or [])
            logs.append({"time": utcnow_iso(), "stage": JOB_STAGE_COMPLETED, "message": message, "details": {}})
            job["logs"] = logs[-MAX_JOB_LOGS:]

        return self._mutate(job_id, mutate)

    def fail_job(
        self,
        job_id: str,
        *,
        error: str,
        stage: str | None = None,
        details: Dict[str, Any] | None = None,
    ) -> Dict[str, Any]:
        failure_stage = stage or JOB_STAGE_FAILED

        def mutate(job: Dict[str, Any]) -> None:
            job["status"] = "failed"
            job["stage"] = failure_stage
            job["error"] = error
            progress = dict(job.get("progress") or {})
            progress["message"] = error
            progress["details"] = dict(details or {})
            job["progress"] = progress
            logs = list(job.get("logs") or [])
            logs.append({"time": utcnow_iso(), "stage": failure_stage, "message": error, "details": dict(details or {})})
            job["logs"] = logs[-MAX_JOB_LOGS:]

        return self._mutate(job_id, mutate)

    def _mutate(self, job_id: str, mutate: Callable[[Dict[str, Any]], None]) -> Dict[str, Any]:
        with self._lock:
            job = self._read_job(job_id)
            mutate(job)
            job["updatedAt"] = utcnow_iso()
            self._write_job(job)
            return job

    def _job_path(self, job_id: str) -> Path:
        return self.jobs_dir / f"{job_id}.json"

    def _read_job(self, job_id: str) -> Dict[str, Any]:
        path = self._job_path(job_id)
        if not path.exists():
            raise KeyError(job_id)
        return json.loads(path.read_text(encoding="utf-8"))

    def _write_job(self, payload: Dict[str, Any]) -> None:
        path = self._job_path(str(payload["jobId"]))
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def create_app(config: ConsoleConfig | None = None) -> FastAPI:
    config = config or ConsoleConfig.from_env()
    ensure_console_directories(config)
    job_store = JobStore(config.jobs_dir)
    app = FastAPI(title="Benchmark Console", version="1.0.0")
    app.state.console_config = config
    app.state.job_store = job_store

    @app.get("/")
    async def index() -> FileResponse:
        if not config.static_html_path.exists():
            raise HTTPException(status_code=500, detail=f"Missing console HTML: {config.static_html_path}")
        return FileResponse(config.static_html_path)

    @app.get("/api/packages")
    async def list_packages_endpoint() -> Dict[str, Any]:
        return {"packages": list_package_records(config)}

    @app.get("/api/packages/inspect")
    async def inspect_package_endpoint(packagePath: str) -> Dict[str, Any]:
        package_dir = resolve_package_path(config, packagePath)
        return inspect_package_dir(package_dir, preview_limit=config.package_preview_limit)

    @app.post("/api/packages/build-upload")
    async def build_upload_endpoint(request: Request) -> Dict[str, Any]:
        try:
            form = await request.form()
        except Exception as exc:
            raise HTTPException(
                status_code=500,
                detail=f"Failed to parse multipart upload. Install python-multipart in the benchmark environment. {exc}",
            ) from exc
        project_key = str(form.get("projectKey") or "").strip()
        suite_version = str(form.get("suiteVersion") or "").strip()
        if not project_key:
            raise HTTPException(status_code=400, detail="projectKey is required")
        if not suite_version:
            raise HTTPException(status_code=400, detail="suiteVersion is required")
        files = list(form.getlist("files"))
        if not files:
            raise HTTPException(status_code=400, detail="files is required")

        relative_paths = [str(item) for item in form.getlist("relative_paths")]
        job = job_store.create_job(
            job_type="build_package",
            stage="uploading",
            status="running",
            input_payload={
                "projectKey": project_key,
                "suiteVersion": suite_version,
                "fileCount": len(files),
            },
        )
        upload_root = config.uploads_dir / job["jobId"]
        upload_root.mkdir(parents=True, exist_ok=True)
        try:
            persist_uploaded_files(
                files=files,
                relative_paths=relative_paths,
                upload_root=upload_root,
                job_store=job_store,
                job_id=job["jobId"],
            )
        except Exception as exc:
            await close_uploaded_files(files)
            job_store.fail_job(job["jobId"], error=str(exc), stage="uploading")
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        await close_uploaded_files(files)

        start_background_job(
            target=run_build_package_job,
            name=f"benchmark-build-{job['jobId']}",
            job_id=job["jobId"],
            config=config,
            job_store=job_store,
            upload_root=upload_root,
            project_key=project_key,
            suite_version=suite_version,
        )
        return {"jobId": job["jobId"]}

    @app.post("/api/builds/import")
    async def import_build_endpoint(request: Request) -> Dict[str, Any]:
        payload = await request.json()
        package_path = str(payload.get("packagePath") or "").strip()
        if not package_path:
            raise HTTPException(status_code=400, detail="packagePath is required")
        package_dir = resolve_package_path(config, package_path)
        job = job_store.create_job(
            job_type="import_build",
            stage="importing_build",
            input_payload={"packagePath": str(package_dir)},
            status="queued",
        )
        start_background_job(
            target=run_import_build_job,
            name=f"benchmark-import-{job['jobId']}",
            job_id=job["jobId"],
            config=config,
            job_store=job_store,
            package_dir=package_dir,
        )
        return {"jobId": job["jobId"]}

    @app.get("/api/builds")
    async def list_builds_endpoint(projectKey: str | None = None, status: str | None = None) -> Dict[str, Any]:
        return {"builds": list_remote_builds(config, project_key=projectKey, status=status)}

    @app.post("/api/runs")
    async def run_benchmark_endpoint(request: Request) -> Dict[str, Any]:
        payload = await request.json()
        package_path = str(payload.get("packagePath") or "").strip()
        build_id = str(payload.get("buildId") or "").strip()
        provider = str(payload.get("provider") or "").strip()
        user_id = str(payload.get("userId") or "benchmark-console").strip() or "benchmark-console"
        session_prefix = str(payload.get("sessionPrefix") or "console").strip() or "console"
        timeout_seconds = int(payload.get("timeoutSeconds") or 180)
        verify_ssl = bool(payload.get("verifySsl") or False)
        if not package_path:
            raise HTTPException(status_code=400, detail="packagePath is required")
        if not build_id:
            raise HTTPException(status_code=400, detail="buildId is required")
        if not provider:
            raise HTTPException(status_code=400, detail="provider is required")

        package_dir = resolve_package_path(config, package_path)
        loaded = load_benchmark_package(package_dir)
        total_samples = len(loaded.benchmark_samples)
        sample_count = int(payload.get("sampleCount") or total_samples)
        if sample_count <= 0:
            raise HTTPException(status_code=400, detail="sampleCount must be greater than 0")
        if sample_count > total_samples:
            raise HTTPException(
                status_code=400,
                detail=f"sampleCount {sample_count} exceeds package sample count {total_samples}",
            )

        job = job_store.create_job(
            job_type="run_benchmark",
            stage="preparing_subset" if sample_count < total_samples else "running_samples",
            input_payload={
                "packagePath": str(package_dir),
                "buildId": build_id,
                "provider": provider,
                "sampleCount": sample_count,
                "totalSampleCount": total_samples,
                "userId": user_id,
                "sessionPrefix": session_prefix,
                "timeoutSeconds": timeout_seconds,
                "verifySsl": verify_ssl,
            },
            status="queued",
        )
        start_background_job(
            target=run_benchmark_job,
            name=f"benchmark-run-{job['jobId']}",
            job_id=job["jobId"],
            config=config,
            job_store=job_store,
            package_dir=package_dir,
            build_id=build_id,
            provider=provider,
            sample_count=sample_count,
            user_id=user_id,
            session_prefix=session_prefix,
            timeout_seconds=timeout_seconds,
            verify_ssl=verify_ssl,
        )
        return {"jobId": job["jobId"]}

    @app.get("/api/jobs/{job_id}")
    async def get_job_endpoint(job_id: str) -> Dict[str, Any]:
        try:
            return job_store.get_job(job_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=f"Unknown jobId: {job_id}") from exc

    @app.get("/api/runs/{job_id}/report")
    async def get_run_report_endpoint(job_id: str) -> Dict[str, Any]:
        try:
            job = job_store.get_job(job_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=f"Unknown jobId: {job_id}") from exc
        if job.get("jobType") != "run_benchmark":
            raise HTTPException(status_code=400, detail="jobId is not a run_benchmark job")
        if job.get("status") != "completed":
            raise HTTPException(status_code=409, detail="Run job has not completed")
        output_dir_text = str((job.get("artifacts") or {}).get("outputDir") or "").strip()
        if not output_dir_text:
            raise HTTPException(status_code=500, detail="Run job is missing outputDir artifact")
        output_dir = Path(output_dir_text).expanduser().resolve()
        return load_run_report(output_dir)

    return app


def ensure_console_directories(config: ConsoleConfig) -> None:
    config.packages_dir.mkdir(parents=True, exist_ok=True)
    config.work_root.mkdir(parents=True, exist_ok=True)
    config.jobs_dir.mkdir(parents=True, exist_ok=True)
    config.uploads_dir.mkdir(parents=True, exist_ok=True)
    config.subpackages_dir.mkdir(parents=True, exist_ok=True)
    config.outputs_dir.mkdir(parents=True, exist_ok=True)


def read_non_negative_int_env(name: str, default: int) -> int:
    text = str(os.getenv(name) or "").strip()
    if not text:
        return default
    try:
        value = int(text)
    except ValueError:
        return default
    return value if value >= 0 else default


def compute_percent(completed: int | None, total: int | None) -> float | None:
    if completed is None or total is None or total <= 0:
        return None
    return round(max(min((completed / total) * 100.0, 100.0), 0.0), 2)


def resolve_package_path(config: ConsoleConfig, package_path: str) -> Path:
    candidate = Path(package_path).expanduser()
    resolved = (candidate if candidate.is_absolute() else config.packages_dir / candidate).resolve()
    try:
        resolved.relative_to(config.packages_dir.resolve())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"packagePath must be under {config.packages_dir}") from exc
    if not resolved.exists() or not resolved.is_dir():
        raise HTTPException(status_code=404, detail=f"Package directory not found: {resolved}")
    return resolved


def list_package_records(config: ConsoleConfig) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    manifest_name = STANDARD_PACKAGE_FILES["suite_manifest"]
    for manifest_path in sorted(config.packages_dir.rglob(manifest_name)):
        package_dir = manifest_path.parent
        try:
            loaded = load_benchmark_package(package_dir)
            validation = validate_package_dir(package_dir)
            record = {
                "packagePath": str(package_dir),
                "relativePackagePath": str(package_dir.relative_to(config.benchmark_root)),
                "projectKey": loaded.manifest.project_key,
                "suiteVersion": loaded.manifest.suite_version,
                "createdAt": loaded.manifest.created_at,
                "generatorVersion": loaded.manifest.generator_version,
                "sampleCount": len(loaded.benchmark_samples),
                "evidenceCount": len(loaded.evidence_units),
                "validationOk": validation.ok,
            }
        except Exception as exc:
            record = {
                "packagePath": str(package_dir),
                "relativePackagePath": str(package_dir.relative_to(config.benchmark_root)),
                "projectKey": "",
                "suiteVersion": package_dir.name,
                "createdAt": "",
                "generatorVersion": "",
                "sampleCount": 0,
                "evidenceCount": 0,
                "validationOk": False,
                "validationError": str(exc),
            }
        records.append(record)
    records.sort(key=lambda item: (item.get("projectKey") or "", item.get("suiteVersion") or ""))
    return records


def inspect_package_dir(package_dir: Path, preview_limit: int = DEFAULT_PACKAGE_PREVIEW_LIMIT) -> Dict[str, Any]:
    loaded = load_benchmark_package(package_dir)
    validation = validate_package_dir(package_dir)
    review_path = package_dir / STANDARD_PACKAGE_FILES["review_markdown"]
    file_descriptors = {}
    for key, file_name in STANDARD_PACKAGE_FILES.items():
        file_path = package_dir / file_name
        file_descriptors[key] = {
            "name": file_name,
            "path": str(file_path),
            "sizeBytes": file_path.stat().st_size if file_path.exists() else 0,
        }
    doc_counts = {}
    for sample in loaded.benchmark_samples:
        for ref in sample.gold_evidence_refs:
            doc_counts[ref.doc_path] = doc_counts.get(ref.doc_path, 0) + 1

    return {
        "packagePath": str(package_dir),
        "manifest": loaded.manifest.to_dict(),
        "files": file_descriptors,
        "sampleCount": len(loaded.benchmark_samples),
        "evidenceCount": len(loaded.evidence_units),
        "samplePreview": [sample.to_dict() for sample in loaded.benchmark_samples[:preview_limit]],
        "evidencePreview": [unit.to_dict() for unit in loaded.evidence_units[:preview_limit]],
        "docSampleCounts": doc_counts,
        "reviewMarkdownPreview": review_path.read_text(encoding="utf-8")[:8000],
        "validation": validation_to_dict(validation),
    }


def validation_to_dict(validation: ValidationResult) -> Dict[str, Any]:
    return {
        "ok": validation.ok,
        "messages": [{"level": message.level, "message": message.message} for message in validation.messages],
    }


def persist_uploaded_files(
    files: List[Any],
    relative_paths: List[str],
    upload_root: Path,
    job_store: JobStore,
    job_id: str,
) -> None:
    if relative_paths and len(relative_paths) != len(files):
        raise ValueError("relative_paths count must match files count")

    total = len(files)
    for index, upload in enumerate(files, start=1):
        filename = str(getattr(upload, "filename", "") or f"upload_{index}")
        relative_path = normalize_upload_relative_path(
            relative_paths[index - 1] if relative_paths else filename,
            fallback_name=filename,
        )
        target_path = (upload_root / relative_path).resolve()
        try:
            target_path.relative_to(upload_root.resolve())
        except ValueError as exc:
            raise ValueError(f"Invalid upload path: {relative_path}") from exc
        target_path.parent.mkdir(parents=True, exist_ok=True)
        source_fp = getattr(upload, "file", None)
        if source_fp is None:
            raise ValueError(f"Upload missing file handle: {filename}")
        source_fp.seek(0)
        with target_path.open("wb") as output_fp:
            shutil.copyfileobj(source_fp, output_fp)
        job_store.set_stage(
            job_id,
            stage="uploading",
            status="running",
            completed=index,
            total=total,
            message=f"Uploaded {relative_path.as_posix()}",
            details={"path": relative_path.as_posix()},
        )


def normalize_upload_relative_path(raw_path: str, fallback_name: str) -> Path:
    text = str(raw_path or "").strip().replace("\\", "/")
    if not text:
        text = fallback_name
    pure_path = PurePosixPath(text)
    if pure_path.is_absolute():
        raise ValueError("Upload paths must be relative")
    parts = [part for part in pure_path.parts if part not in ("", ".")]
    if not parts:
        parts = [fallback_name]
    if any(part == ".." for part in parts):
        raise ValueError("Upload paths must not contain '..'")
    return Path(*parts)


def progress_callback_for_stage(job_store: JobStore, job_id: str, stage: str) -> Callable[[ProgressEvent], None]:
    def callback(event: ProgressEvent) -> None:
        details = {"workflowStage": event.stage}
        details.update(event.details)
        job_store.set_stage(
            job_id,
            stage=stage,
            status="running",
            completed=event.completed,
            total=event.total,
            message=event.message,
            details=details,
        )

    return callback


async def close_uploaded_files(files: List[Any]) -> None:
    for upload in files:
        close = getattr(upload, "close", None)
        if not callable(close):
            continue
        result = close()
        if inspect.isawaitable(result):
            await result


def run_build_package_job(
    *,
    job_id: str,
    config: ConsoleConfig,
    job_store: JobStore,
    upload_root: Path,
    project_key: str,
    suite_version: str,
) -> None:
    try:
        report = build_benchmark_package(
            source_path=upload_root,
            source_root=upload_root,
            project_key=project_key,
            suite_version=suite_version,
            package_root=config.benchmark_root,
            docreader_base_url=config.docreader_base_url,
            progress_callback=progress_callback_for_stage(job_store, job_id, "building_package"),
        )
        job_store.set_stage(
            job_id,
            stage="validating_package",
            status="running",
            completed=0,
            total=1,
            message=f"Validating package {report.package_dir}",
            details={"packagePath": str(report.package_dir)},
        )
        validation = validate_package_dir(report.package_dir)
        if not validation.ok:
            messages = [message.message for message in validation.messages if message.level == "error"]
            raise RuntimeError("; ".join(messages) or f"Package validation failed: {report.package_dir}")
        job_store.complete_job(
            job_id,
            result={
                "packagePath": str(report.package_dir),
                "projectKey": project_key,
                "suiteVersion": suite_version,
                "sourceFileCount": len(report.source_files),
                "normalizedDocumentCount": report.normalized_document_count,
                "evidenceCount": report.evidence_count,
                "sampleCount": report.sample_count,
                "validation": validation_to_dict(validation),
            },
            artifacts={"packagePath": str(report.package_dir)},
            message=f"Package built at {report.package_dir}",
        )
    except Exception as exc:
        job_store.fail_job(job_id, error=str(exc), stage="failed")


def run_import_build_job(
    *,
    job_id: str,
    config: ConsoleConfig,
    job_store: JobStore,
    package_dir: Path,
) -> None:
    try:
        job_store.set_stage(
            job_id,
            stage="importing_build",
            status="running",
            completed=0,
            total=1,
            message=f"Importing package {package_dir}",
            details={"packagePath": str(package_dir)},
        )
        build = import_remote_build(config, package_dir)
        build_id = str(build.get("buildId") or "").strip()
        if not build_id:
            raise RuntimeError("App import-package response missing buildId")

        ready_build = wait_for_remote_build_ready(
            config=config,
            job_store=job_store,
            job_id=job_id,
            build_id=build_id,
            initial_build=build,
        )
        final_status = str(ready_build.get("status") or "").strip().lower()
        if final_status != "ready":
            error_message = str(ready_build.get("errorMessage") or f"Build {build_id} ended with status {final_status}")
            raise RuntimeError(error_message)
        job_store.complete_job(
            job_id,
            result={"build": ready_build},
            artifacts={"packagePath": str(package_dir), "buildId": build_id},
            message=f"Build {build_id} is ready",
        )
    except Exception as exc:
        job_store.fail_job(job_id, error=str(exc), stage="failed")


def run_benchmark_job(
    *,
    job_id: str,
    config: ConsoleConfig,
    job_store: JobStore,
    package_dir: Path,
    build_id: str,
    provider: str,
    sample_count: int,
    user_id: str,
    session_prefix: str,
    timeout_seconds: int,
    verify_ssl: bool,
) -> None:
    try:
        loaded = load_benchmark_package(package_dir)
        selected_package_dir = package_dir
        subset_result: SubsetPackageResult | None = None

        if sample_count < len(loaded.benchmark_samples):
            seed = int(time.time())
            job_store.set_stage(
                job_id,
                stage="preparing_subset",
                status="running",
                completed=0,
                total=1,
                message=f"Creating random subset of {sample_count} samples",
                details={"seed": seed, "sourcePackagePath": str(package_dir)},
            )
            subset_result = create_random_subset_package(
                package_dir=package_dir,
                sample_count=sample_count,
                seed=seed,
                output_root=config.subpackages_dir / job_id,
                suite_version_suffix=f"subset_{sample_count}_{job_id[:8]}",
            )
            selected_package_dir = subset_result.package_dir
            job_store.merge_result(
                job_id,
                {
                    "subset": subset_to_dict(subset_result),
                },
            )

        request = RunBenchmarkRequest(
            package_dir=selected_package_dir,
            base_url=config.app_base_url,
            provider=provider,
            build_id=build_id,
            user_id=user_id,
            session_prefix=session_prefix,
            timeout_seconds=timeout_seconds,
            output_root=config.outputs_dir / job_id,
            verify_ssl=verify_ssl,
        )
        report = run_benchmark(
            request,
            progress_callback=progress_callback_for_stage(job_store, job_id, "running_samples"),
        )
        artifacts = write_benchmark_outputs(report)
        ragas_artifacts = evaluate_ragas_for_report(
            report,
            artifacts.output_dir,
            progress_callback=progress_callback_for_stage(job_store, job_id, "evaluating_ragas"),
        )
        output_payload = {
            "outputDir": str(artifacts.output_dir),
            "runMetaPath": str(artifacts.run_meta_path),
            "samplesCollectedPath": str(artifacts.samples_collected_path),
            "benchmarkReportJsonPath": str(artifacts.benchmark_report_json_path),
            "benchmarkReportMarkdownPath": str(artifacts.benchmark_report_markdown_path),
            "ragasSummaryPath": str(ragas_artifacts.summary_path) if ragas_artifacts.summary_path else None,
            "ragasPerSampleCsvPath": (
                str(ragas_artifacts.per_sample_csv_path) if ragas_artifacts.per_sample_csv_path else None
            ),
            "ragasErrorPath": str(ragas_artifacts.error_path) if ragas_artifacts.error_path else None,
        }
        summary = load_run_report(artifacts.output_dir)
        job_store.complete_job(
            job_id,
            result={
                "projectKey": report.project_key,
                "suiteVersion": report.suite_version,
                "sampleCount": report.sample_count,
                "selectedPackagePath": str(selected_package_dir),
                "buildId": build_id,
                "provider": provider,
                "summary": summary.get("summary"),
                "ragasSummary": summary.get("ragasSummary"),
                "subset": subset_to_dict(subset_result) if subset_result else None,
            },
            artifacts=output_payload,
            message=f"Benchmark run completed with {report.sample_count} samples",
        )
    except Exception as exc:
        job_store.fail_job(job_id, error=str(exc), stage="failed")


def subset_to_dict(subset: SubsetPackageResult | None) -> Dict[str, Any] | None:
    if subset is None:
        return None
    payload = asdict(subset)
    payload["package_dir"] = str(subset.package_dir)
    return payload


def list_remote_builds(config: ConsoleConfig, project_key: str | None = None, status: str | None = None) -> List[Dict[str, Any]]:
    params = {}
    if project_key:
        params["projectKey"] = project_key
    if status:
        params["status"] = status
    response = requests.get(
        f"{config.app_base_url.rstrip('/')}/api/benchmark/builds",
        params=params,
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, list):
        raise RuntimeError("Build list API returned non-list payload")
    return [item for item in payload if isinstance(item, dict)]


def import_remote_build(config: ConsoleConfig, package_dir: Path) -> Dict[str, Any]:
    response = requests.post(
        f"{config.app_base_url.rstrip('/')}/api/benchmark/builds/import-package",
        json={"packagePath": str(package_dir)},
        timeout=120,
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise RuntimeError("Build import API returned non-object payload")
    return payload


def get_remote_build(config: ConsoleConfig, build_id: str) -> Dict[str, Any]:
    response = requests.get(
        f"{config.app_base_url.rstrip('/')}/api/benchmark/builds/{build_id}",
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise RuntimeError("Build detail API returned non-object payload")
    return payload


def wait_for_remote_build_ready(
    *,
    config: ConsoleConfig,
    job_store: JobStore,
    job_id: str,
    build_id: str,
    initial_build: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    deadline = time.monotonic() + config.build_ready_timeout_seconds
    last_status = ""
    poll_count = 0
    build = dict(initial_build or {})
    while True:
        if not build:
            build = get_remote_build(config, build_id)
        poll_count += 1
        status = str(build.get("status") or "").strip().lower()
        if status != last_status:
            job_store.set_stage(
                job_id,
                stage="waiting_build_ready",
                status="running",
                completed=1 if status in {"ready", "failed"} else 0,
                total=1,
                message=f"Build {build_id} status: {status or 'unknown'}",
                details={"buildId": build_id, "status": status, "pollCount": poll_count},
            )
            last_status = status
        if status in {"ready", "failed"}:
            return build
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for build {build_id} to become ready")
        time.sleep(config.build_poll_interval_seconds)
        build = {}


def load_run_report(output_dir: Path) -> Dict[str, Any]:
    benchmark_report_path = output_dir / "benchmark_report.json"
    ragas_summary_path = output_dir / "ragas_summary.json"
    ragas_error_path = output_dir / "ragas_error.json"
    ragas_csv_path = output_dir / "ragas_scores_per_sample.csv"

    benchmark_report = read_json_file(benchmark_report_path)
    ragas_summary = read_optional_json_file(ragas_summary_path)
    ragas_error = read_optional_json_file(ragas_error_path)
    ragas_rows = read_ragas_csv(ragas_csv_path)

    sample_details = []
    for sample in benchmark_report.get("samples", []):
        if not isinstance(sample, dict):
            continue
        sample_id = str(sample.get("sample_id") or "").strip()
        ragas_row = ragas_rows.get(sample_id, {})
        sample_details.append(
            {
                "sampleId": sample_id,
                "question": sample.get("question") or "",
                "finalAnswer": sample.get("final_answer") or "",
                "groundTruth": sample.get("ground_truth") or "",
                "finishReason": sample.get("finish_reason") or "",
                "latencyMs": sample.get("latency_ms"),
                "error": sample.get("error"),
                "contextOutputChunkCount": count_context_output_hits_from_payload(sample),
                "groundTruthContexts": sample.get("ground_truth_contexts") or [],
                "goldEvidenceRefs": sample.get("gold_evidence_refs") or [],
                "ragasScores": ragas_row.get("scores") or {},
                "ragasRow": ragas_row.get("row") or {},
            }
        )

    return {
        "outputDir": str(output_dir),
        "runMeta": benchmark_report.get("run_meta") or {},
        "summary": benchmark_report.get("summary") or {},
        "failedSamples": benchmark_report.get("failed_samples") or [],
        "samples": sample_details,
        "ragasSummary": ragas_summary,
        "ragasError": ragas_error,
        "artifacts": {
            "benchmarkReportJsonPath": str(benchmark_report_path),
            "ragasSummaryPath": str(ragas_summary_path) if ragas_summary_path.exists() else None,
            "ragasErrorPath": str(ragas_error_path) if ragas_error_path.exists() else None,
            "ragasPerSampleCsvPath": str(ragas_csv_path) if ragas_csv_path.exists() else None,
        },
    }


def count_context_output_hits_from_payload(sample_payload: Dict[str, Any]) -> int:
    payload_sample = type(
        "PayloadSample",
        (),
        {"retrieval_trace_records": sample_payload.get("retrieval_trace_records") or []},
    )()
    return count_context_output_hits(payload_sample)


def read_json_file(path: Path) -> Dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise RuntimeError(f"Expected JSON object in {path}")
    return payload


def read_optional_json_file(path: Path) -> Dict[str, Any] | None:
    if not path.exists():
        return None
    return read_json_file(path)


def read_ragas_csv(path: Path) -> Dict[str, Dict[str, Any]]:
    if not path.exists() or path.stat().st_size == 0:
        return {}

    output: Dict[str, Dict[str, Any]] = {}
    with path.open("r", encoding="utf-8", newline="") as fp:
        reader = csv.DictReader(fp)
        for row in reader:
            if not isinstance(row, dict):
                continue
            sample_id = str(row.get("sample_id") or "").strip()
            if not sample_id:
                continue
            scores = {}
            normalized_row = {}
            for key, value in row.items():
                normalized_value = normalize_csv_value(value)
                normalized_row[key] = normalized_value
                if key in RAGAS_CSV_BASE_FIELDS:
                    continue
                scores[key] = normalized_value
            output[sample_id] = {"row": normalized_row, "scores": scores}
    return output


def normalize_csv_value(value: Any) -> Any:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        numeric = float(text)
    except ValueError:
        return text
    if not math.isfinite(numeric):
        return None
    if numeric.is_integer():
        return int(numeric)
    return numeric


def start_background_job(target: Callable[..., None], name: str, **kwargs: Any) -> None:
    thread = threading.Thread(target=target, name=name, kwargs=kwargs, daemon=True)
    thread.start()


app = create_app()


def main() -> None:
    config = ConsoleConfig.from_env()
    app_instance = create_app(config)

    import uvicorn

    uvicorn.run(app_instance, host=config.host, port=config.port)


if __name__ == "__main__":
    main()
