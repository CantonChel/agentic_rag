import asyncio
import hashlib
import hmac
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Dict

import httpx

from config import settings
from models import CallbackError, CallbackPayload, JobSubmitRequest, JobStatus
from parser import ParseError, parse_to_chunks

logger = logging.getLogger(__name__)


@dataclass
class InternalJob:
    remote_job_id: str
    request: JobSubmitRequest


class JobManager:
    def __init__(self) -> None:
        self._queue: "asyncio.Queue[InternalJob]" = asyncio.Queue()
        self._statuses: Dict[str, JobStatus] = {}
        self._workers = []
        self._running = False

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        for idx in range(max(1, settings.worker_count)):
            self._workers.append(asyncio.create_task(self._run_worker(idx), name=f"docreader-worker-{idx}"))

    async def stop(self) -> None:
        self._running = False
        for task in self._workers:
            task.cancel()
        self._workers.clear()

    async def submit(self, req: JobSubmitRequest) -> str:
        remote_job_id = str(uuid.uuid4())
        self._statuses[remote_job_id] = JobStatus(remote_job_id=remote_job_id, status="queued")
        await self._queue.put(InternalJob(remote_job_id=remote_job_id, request=req))
        return remote_job_id

    def get_status(self, remote_job_id: str) -> JobStatus:
        return self._statuses.get(remote_job_id, JobStatus(remote_job_id=remote_job_id, status="not_found"))

    async def _run_worker(self, _worker_id: int) -> None:
        while True:
            job = await self._queue.get()
            self._statuses[job.remote_job_id] = JobStatus(remote_job_id=job.remote_job_id, status="running")
            try:
                await self._process_job(job)
                self._statuses[job.remote_job_id] = JobStatus(remote_job_id=job.remote_job_id, status="success")
            except Exception as exc:
                self._statuses[job.remote_job_id] = JobStatus(remote_job_id=job.remote_job_id, status="failed", message=str(exc))
            finally:
                self._queue.task_done()

    async def _process_job(self, job: InternalJob) -> None:
        req = job.request
        event_id = str(uuid.uuid4())
        try:
            chunks = await parse_to_chunks(req.job_id, req.file_url, req.options)
            payload = CallbackPayload(
                event_id=event_id,
                status="success",
                message="ok",
                chunks=chunks,
            )
        except ParseError as exc:
            payload = CallbackPayload(
                event_id=event_id,
                status="failed",
                message=exc.message,
                error=CallbackError(code=exc.code, message=exc.message),
                chunks=[],
            )
        except Exception as exc:
            payload = CallbackPayload(
                event_id=event_id,
                status="failed",
                message=str(exc),
                error=CallbackError(code="service_unavailable", message=str(exc)),
                chunks=[],
            )

        await self._callback(req.callback_url, payload)

    async def _callback(self, callback_url: str, payload: CallbackPayload) -> None:
        body = payload.json(by_alias=True, ensure_ascii=False)
        timestamp = str(int(time.time()))
        headers = {
            "Content-Type": "application/json",
            settings.timestamp_header: timestamp,
        }
        if settings.callback_secret:
            sign_base = f"{timestamp}.{body}".encode("utf-8")
            signature = hmac.new(settings.callback_secret.encode("utf-8"), sign_base, hashlib.sha256).hexdigest()
            headers[settings.signature_header] = signature

        timeout = httpx.Timeout(settings.callback_timeout_seconds)
        attempts = max(1, settings.callback_retry_max)
        for attempt in range(1, attempts + 1):
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    resp = await client.post(callback_url, content=body.encode("utf-8"), headers=headers)
                    if 200 <= resp.status_code < 300:
                        return
                    logger.warning(
                        "docreader callback non-2xx: attempt=%s/%s url=%s status=%s body=%s",
                        attempt,
                        attempts,
                        callback_url,
                        resp.status_code,
                        (resp.text or "")[:300],
                    )
            except Exception:
                logger.exception(
                    "docreader callback exception: attempt=%s/%s url=%s",
                    attempt,
                    attempts,
                    callback_url,
                )
                await asyncio.sleep(1)
        logger.error(
            "docreader callback failed after retries: attempts=%s url=%s event_id=%s status=%s",
            attempts,
            callback_url,
            payload.event_id,
            payload.status,
        )


job_manager = JobManager()
