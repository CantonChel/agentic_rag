"""HTTP client helpers for the stage-6 benchmark runner."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Dict
from typing import Iterable
from typing import List

import requests


@dataclass(frozen=True)
class AgentStreamCapture:
    """Minimal structured data extracted from one SSE call."""

    turn_id: str | None
    event_count: int
    event_type_counts: Dict[str, int]
    transport_error: str | None = None


class BenchmarkAppClient:
    """Small HTTP client for agentic_rag_app benchmark APIs."""

    def __init__(self, base_url: str, timeout_seconds: int, verify_ssl: bool) -> None:
        self.base_url = normalize_base_url(base_url)
        self.timeout_seconds = timeout_seconds
        self.verify_ssl = verify_ssl

    def stream_agent_turn(
        self,
        provider: str,
        user_id: str,
        session_id: str,
        prompt: str,
        build_id: str,
        tools: bool,
        tool_choice: str,
    ) -> AgentStreamCapture:
        url = f"{self.base_url}/api/agent/{provider}/stream"
        params = {
            "userId": user_id,
            "sessionId": session_id,
            "prompt": prompt,
            "buildId": build_id,
            "kbScope": "BENCHMARK_BUILD",
            "evalMode": "SINGLE_TURN",
            "thinkingProfile": "HIDE",
            "memoryEnabled": "false",
            "tools": str(tools).lower(),
            "toolChoice": tool_choice,
        }
        with requests.get(
            url,
            params=params,
            stream=True,
            timeout=(10, self.timeout_seconds),
            verify=self.verify_ssl,
        ) as response:
            response.raise_for_status()
            return collect_agent_stream_capture(response.iter_lines(decode_unicode=True))

    def get_turn_summary(self, turn_id: str) -> dict:
        response = requests.get(
            f"{self.base_url}/api/benchmark/turn-summaries/{turn_id}",
            timeout=self.timeout_seconds,
            verify=self.verify_ssl,
        )
        if response.status_code == 404:
            raise RuntimeError(f"turn summary not found for turnId={turn_id}")
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise RuntimeError("turn summary API returned non-object payload")
        return payload

    def get_retrieval_traces(self, trace_id: str) -> List[dict]:
        response = requests.get(
            f"{self.base_url}/api/benchmark/retrieval-traces",
            params={"traceId": trace_id},
            timeout=self.timeout_seconds,
            verify=self.verify_ssl,
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, list):
            raise RuntimeError("retrieval trace API returned non-list payload")
        return [item for item in payload if isinstance(item, dict)]


def normalize_base_url(base_url: str) -> str:
    return base_url[:-1] if base_url.endswith("/") else base_url


def collect_agent_stream_capture(lines: Iterable[str]) -> AgentStreamCapture:
    """Parse streamed SSE lines and keep only benchmark runner essentials."""

    turn_id: str | None = None
    event_count = 0
    event_type_counts: Dict[str, int] = {}
    transport_error: str | None = None

    for raw_line in lines:
        if raw_line is None:
            continue
        line = raw_line.strip()
        if not line or not line.startswith("data:"):
            continue

        payload = line[5:].strip()
        if not payload:
            continue

        try:
            event = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue

        event_count += 1
        event_type = str(event.get("type") or "").strip() or "unknown"
        event_type_counts[event_type] = event_type_counts.get(event_type, 0) + 1

        if turn_id is None:
            candidate_turn_id = str(event.get("turnId") or "").strip()
            if candidate_turn_id:
                turn_id = candidate_turn_id

        if event_type == "error":
            transport_error = str(event.get("content") or "Unknown agent stream error")

    return AgentStreamCapture(
        turn_id=turn_id,
        event_count=event_count,
        event_type_counts=event_type_counts,
        transport_error=transport_error,
    )
