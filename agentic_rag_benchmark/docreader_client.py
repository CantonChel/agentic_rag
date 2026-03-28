"""Thin client for reusing the external docreader service."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any
from typing import Dict
from typing import List
import uuid

import requests


@dataclass(frozen=True)
class DocreaderImageRef:
    original_ref: str
    file_name: str
    mime_type: str
    bytes_base64: str | None


@dataclass(frozen=True)
class DocreaderReadResult:
    markdown_content: str
    image_refs: List[DocreaderImageRef]
    metadata: Dict[str, str]
    error: str


class DocreaderServiceError(RuntimeError):
    """Raised when docreader returns an explicit error payload."""


class DocreaderServiceClient:
    """HTTP client used by the benchmark package to call docreader."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8090",
        read_path: str = "/read",
        timeout_seconds: int = 120,
        session: requests.Session | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.read_path = read_path if read_path.startswith("/") else f"/{read_path}"
        self.timeout_seconds = timeout_seconds
        self.session = session or requests.Session()

    def read_path_file(
        self,
        source_path: Path,
        pipeline_version: str = "v1",
        options: Dict[str, Any] | None = None,
    ) -> DocreaderReadResult:
        resolved = source_path.expanduser().resolve()
        payload = {
            "jobId": f"benchmark-{uuid.uuid4()}",
            "knowledgeId": f"benchmark-{uuid.uuid4()}",
            "fileUrl": str(resolved),
            "pipelineVersion": pipeline_version,
            "options": options or {},
        }
        response = self.session.post(
            f"{self.base_url}{self.read_path}",
            json=payload,
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        data = response.json()
        result = DocreaderReadResult(
            markdown_content=str(data.get("markdownContent") or ""),
            image_refs=[
                DocreaderImageRef(
                    original_ref=str(item.get("originalRef") or ""),
                    file_name=str(item.get("fileName") or ""),
                    mime_type=str(item.get("mimeType") or ""),
                    bytes_base64=(str(item.get("bytesBase64")) if item.get("bytesBase64") is not None else None),
                )
                for item in data.get("imageRefs", [])
                if isinstance(item, dict)
            ],
            metadata={
                str(key): str(value)
                for key, value in (data.get("metadata") or {}).items()
            },
            error=str(data.get("error") or ""),
        )
        if result.error:
            raise DocreaderServiceError(result.error)
        return result
