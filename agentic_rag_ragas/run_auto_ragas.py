#!/usr/bin/env python3
"""Automated RAGAS evaluator for agentic_rag_app.

Flow per sample:
1) Call /api/agent/{provider}/stream to get final assistant answer.
2) Fetch /api/sessions/{sessionId}/messages.
3) Extract retrieved contexts from TOOL_RESULT(search_knowledge_base).
4) Run RAGAS and save reports.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import requests
from dotenv import load_dotenv


CONTEXT_BLOCK_PATTERN = re.compile(
    r"\[引用\s*\d+\]\s*来源:\s*(.*?)\n(.*?)(?=(?:\n\[引用\s*\d+\]\s*来源:)|(?:\n</context>)|$)",
    re.DOTALL,
)
CODE_BLOCK_PATTERN = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL | re.IGNORECASE)

PROMPT_SIGNATURE_CONTEXT_PRECISION = "Given question, answer and context verify if the context was useful"
PROMPT_SIGNATURE_FAITHFULNESS_NLI = "Your task is to judge the faithfulness of a series of statements based on a given context."
PROMPT_SIGNATURE_FAITHFULNESS_BREAKDOWN = "Given a question, an answer, and sentences from the answer analyze the complexity"
PROMPT_SIGNATURE_FORMAT_REPAIR = "Below, the Completion did not satisfy the constraints given in the Prompt."


@dataclass
class EvalSample:
    sample_id: str
    question: str
    ground_truth: str
    provider: str
    user_id: str


@dataclass
class EvalRecord:
    sample_id: str
    question: str
    ground_truth: str
    answer: str
    contexts: List[str]
    sources: List[str]
    provider: str
    user_id: str
    session_id: str
    stream_event_count: int
    search_call_count: int
    error: Optional[str] = None


def load_project_env() -> None:
    """Load .env from current dir and project root (agentic_rag/.env)."""
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent
    local_env = script_dir / ".env"
    root_env = project_root / ".env"

    if root_env.exists():
        load_dotenv(dotenv_path=root_env, override=False)
    if local_env.exists():
        load_dotenv(dotenv_path=local_env, override=False)
    # Keep default behavior as a fallback.
    load_dotenv(override=False)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run automatic RAGAS for agentic_rag_app")
    parser.add_argument(
        "--dataset",
        required=True,
        help="Path to dataset JSONL/JSON. Required fields: question + ground_truth/reference",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("APP_BASE_URL", "http://127.0.0.1:8081"),
        help="agentic_rag_app base URL",
    )
    parser.add_argument("--provider", default="openai", choices=["openai", "minimax"])
    parser.add_argument("--user-id", default="ragas-eval")
    parser.add_argument("--session-prefix", default="ragas")
    parser.add_argument("--tool-choice", default="AUTO", choices=["AUTO", "NONE", "REQUIRED"])
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument(
        "--output-dir",
        default=str(Path(__file__).resolve().parent / "outputs"),
        help="Directory for reports",
    )
    parser.add_argument(
        "--verify-ssl",
        action="store_true",
        help="Verify HTTPS certificates (default false for local dev)",
    )
    parser.add_argument(
        "--trace-ragas-judge",
        action="store_true",
        help="Capture raw RAGAS judge prompt/output traces and schema diagnostics",
    )
    parser.set_defaults(verify_ssl=False)

    tools_group = parser.add_mutually_exclusive_group()
    tools_group.add_argument("--tools", action="store_true", help="Enable tools in agent call (default)")
    tools_group.add_argument("--no-tools", action="store_true", help="Disable tools in agent call")
    parser.set_defaults(tools=True)

    args = parser.parse_args()
    if args.no_tools:
        args.tools = False
    if args.base_url.endswith("/"):
        args.base_url = args.base_url[:-1]
    return args


def load_samples(dataset_path: Path, default_provider: str, default_user_id: str) -> List[EvalSample]:
    if not dataset_path.exists():
        raise FileNotFoundError(f"Dataset not found: {dataset_path}")

    raw_items: List[Dict[str, Any]]
    if dataset_path.suffix.lower() == ".jsonl":
        raw_items = []
        for line_no, line in enumerate(dataset_path.read_text(encoding="utf-8").splitlines(), start=1):
            text = line.strip()
            if not text:
                continue
            try:
                raw_items.append(json.loads(text))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at line {line_no}: {exc}") from exc
    else:
        payload = json.loads(dataset_path.read_text(encoding="utf-8"))
        if isinstance(payload, dict) and isinstance(payload.get("samples"), list):
            raw_items = payload["samples"]
        elif isinstance(payload, list):
            raw_items = payload
        else:
            raise ValueError("Dataset JSON must be a list or an object with `samples` list")

    samples: List[EvalSample] = []
    for idx, item in enumerate(raw_items, start=1):
        if not isinstance(item, dict):
            raise ValueError(f"Dataset row #{idx} must be an object")

        question = str(item.get("question", "")).strip()
        if not question:
            raise ValueError(f"Dataset row #{idx} missing `question`")

        ground_truth = ""
        for key in ("ground_truth", "reference", "answer"):
            value = item.get(key)
            if value is not None and str(value).strip():
                ground_truth = str(value).strip()
                break

        sample_id = str(item.get("id") or f"q{idx:03d}")
        provider = str(item.get("provider") or default_provider)
        user_id = str(item.get("user_id") or default_user_id)

        samples.append(
            EvalSample(
                sample_id=sample_id,
                question=question,
                ground_truth=ground_truth,
                provider=provider,
                user_id=user_id,
            )
        )

    if not samples:
        raise ValueError("Dataset has no valid rows")
    return samples


def stream_agent_answer(
    base_url: str,
    provider: str,
    user_id: str,
    session_id: str,
    prompt: str,
    timeout_seconds: int,
    tools: bool,
    tool_choice: str,
    verify_ssl: bool,
) -> Tuple[str, List[Dict[str, Any]]]:
    url = f"{base_url}/api/agent/{provider}/stream"
    params = {
        "userId": user_id,
        "sessionId": session_id,
        "prompt": prompt,
        "tools": str(tools).lower(),
        "toolChoice": tool_choice,
    }

    answer_parts: List[str] = []
    events: List[Dict[str, Any]] = []
    saw_done = False

    with requests.get(
        url,
        params=params,
        stream=True,
        timeout=(10, timeout_seconds),
        verify=verify_ssl,
    ) as response:
        response.raise_for_status()

        for raw_line in response.iter_lines(decode_unicode=True):
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

            events.append(event)
            event_type = str(event.get("type", ""))

            if event_type == "delta":
                answer_parts.append(str(event.get("content") or ""))
            elif event_type == "error":
                raise RuntimeError(str(event.get("content") or "Unknown agent stream error"))
            elif event_type == "done":
                saw_done = True
                break

    if not saw_done:
        raise RuntimeError("Agent stream ended without done event")

    return "".join(answer_parts).strip(), events


def fetch_session_messages(
    base_url: str,
    session_id: str,
    user_id: str,
    timeout_seconds: int,
    verify_ssl: bool,
) -> List[Dict[str, Any]]:
    url = f"{base_url}/api/sessions/{session_id}/messages"
    response = requests.get(
        url,
        params={"userId": user_id},
        timeout=timeout_seconds,
        verify=verify_ssl,
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, list):
        raise RuntimeError("messages API returned non-list payload")
    return [x for x in payload if isinstance(x, dict)]


def parse_contexts_from_tool_result(result: str) -> Tuple[List[str], List[str]]:
    contexts: List[str] = []
    sources: List[str] = []
    if not result:
        return contexts, sources

    body = result
    if "<context>" in result and "</context>" in result:
        start = result.find("<context>") + len("<context>")
        end = result.rfind("</context>")
        body = result[start:end]

    for match in CONTEXT_BLOCK_PATTERN.finditer(body):
        source = match.group(1).strip()
        text = match.group(2).strip()
        if not text:
            continue
        contexts.append(text)
        sources.append(source)

    if not contexts and body.strip():
        contexts.append(body.strip())
        sources.append("")

    return contexts, sources


def extract_contexts_from_messages(messages: Iterable[Dict[str, Any]]) -> Tuple[List[str], List[str], int]:
    all_contexts: List[str] = []
    all_sources: List[str] = []
    search_call_count = 0

    for msg in messages:
        if str(msg.get("type")) != "TOOL_RESULT":
            continue
        tool_name = str(msg.get("toolName") or "")
        if tool_name != "search_knowledge_base":
            continue
        if msg.get("success") is False:
            continue

        search_call_count += 1
        raw_result = str(msg.get("result") or msg.get("content") or "")
        contexts, sources = parse_contexts_from_tool_result(raw_result)
        all_contexts.extend(contexts)
        all_sources.extend(sources)

    dedup_contexts: List[str] = []
    dedup_sources: List[str] = []
    seen = set()

    for ctx, src in zip(all_contexts, all_sources):
        key = re.sub(r"\s+", " ", ctx).strip()
        if not key or key in seen:
            continue
        seen.add(key)
        dedup_contexts.append(ctx)
        dedup_sources.append(src)

    return dedup_contexts, dedup_sources, search_call_count


def get_last_assistant_message(messages: Iterable[Dict[str, Any]]) -> str:
    for msg in reversed(list(messages)):
        if str(msg.get("type")) == "ASSISTANT":
            content = str(msg.get("content") or "").strip()
            if content:
                return content
    return ""


def ensure_output_dir(base_dir: Path) -> Path:
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    run_dir = base_dir / f"run_{timestamp}"
    run_dir.mkdir(parents=True, exist_ok=False)
    return run_dir


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Dict[str, Any]]) -> None:
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


class JsonlTraceWriter:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._seq = 0

    def append(self, event: Dict[str, Any]) -> None:
        with self._lock:
            self._seq += 1
            payload = {
                "seq": self._seq,
                "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                **event,
            }
            with self.path.open("a", encoding="utf-8") as fp:
                fp.write(json.dumps(payload, ensure_ascii=False) + "\n")


def classify_ragas_prompt(prompt_text: str) -> str:
    if PROMPT_SIGNATURE_CONTEXT_PRECISION in prompt_text:
        return "context_precision_verification"
    if PROMPT_SIGNATURE_FAITHFULNESS_NLI in prompt_text:
        return "faithfulness_nli"
    if PROMPT_SIGNATURE_FAITHFULNESS_BREAKDOWN in prompt_text:
        return "faithfulness_statement_breakdown"
    if PROMPT_SIGNATURE_FORMAT_REPAIR in prompt_text:
        return "format_repair"
    return "unknown"


def parse_json_payload(raw_text: str) -> Tuple[Optional[Any], Optional[str], Optional[str]]:
    text = str(raw_text or "").strip()
    if not text:
        return None, None, "empty_output"

    try:
        return json.loads(text), text, None
    except Exception:
        pass

    match = CODE_BLOCK_PATTERN.search(text)
    if match:
        candidate = match.group(1).strip()
        try:
            return json.loads(candidate), candidate, None
        except Exception as exc:
            return None, candidate, f"json_decode_error:{exc}"

    return None, None, "json_not_found"


def _is_int_like(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    if isinstance(value, str):
        return value.strip() in {"0", "1"}
    return False


def validate_schema(schema_name: str, payload: Any) -> Tuple[bool, str]:
    if schema_name == "context_precision_verification":
        if not isinstance(payload, dict):
            return False, "expected_object"
        if "reason" not in payload:
            return False, "missing_reason"
        if "verdict" not in payload:
            return False, "missing_verdict"
        if not isinstance(payload.get("reason"), str) or not payload.get("reason", "").strip():
            return False, "invalid_reason"
        if not _is_int_like(payload.get("verdict")):
            return False, "invalid_verdict"
        return True, "ok"

    if schema_name == "faithfulness_nli":
        if not isinstance(payload, list):
            return False, "expected_array"
        for idx, item in enumerate(payload):
            if not isinstance(item, dict):
                return False, f"item_{idx}_not_object"
            for key in ("statement", "reason", "verdict"):
                if key not in item:
                    return False, f"item_{idx}_missing_{key}"
            if not isinstance(item["statement"], str) or not item["statement"].strip():
                return False, f"item_{idx}_invalid_statement"
            if not isinstance(item["reason"], str) or not item["reason"].strip():
                return False, f"item_{idx}_invalid_reason"
            if not _is_int_like(item["verdict"]):
                return False, f"item_{idx}_invalid_verdict"
        return True, "ok"

    if schema_name == "faithfulness_statement_breakdown":
        if not isinstance(payload, list):
            return False, "expected_array"
        for idx, item in enumerate(payload):
            if not isinstance(item, dict):
                return False, f"item_{idx}_not_object"
            if "sentence_index" not in item or "simpler_statements" not in item:
                return False, f"item_{idx}_missing_fields"
            if not _is_int_like(item["sentence_index"]):
                return False, f"item_{idx}_invalid_sentence_index"
            if not isinstance(item["simpler_statements"], list):
                return False, f"item_{idx}_invalid_simpler_statements"
        return True, "ok"

    if schema_name == "format_repair":
        # Format-repair prompt only asks for corrected completion text.
        if isinstance(payload, (dict, list)):
            return True, "ok"
        return False, "expected_json_after_repair"

    return True, "skip_unknown"


def diagnose_judge_trace(trace_path: Path) -> Dict[str, Any]:
    total_outputs = 0
    parse_ok = 0
    schema_ok = 0
    failures: List[Dict[str, Any]] = []
    by_schema: Dict[str, Dict[str, int]] = {}

    if not trace_path.exists():
        return {
            "trace_file": str(trace_path),
            "exists": False,
            "total_outputs": 0,
            "parse_ok": 0,
            "schema_ok": 0,
            "failures": [],
        }

    for line_no, line in enumerate(trace_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except Exception:
            failures.append(
                {
                    "line": line_no,
                    "schema": "trace_line",
                    "stage": "trace_decode",
                    "reason": "invalid_jsonl_event",
                    "snippet": line[:200],
                }
            )
            continue

        if event.get("event_type") != "judge_generation":
            continue

        schema_name = str(event.get("expected_schema") or "unknown")
        stats = by_schema.setdefault(schema_name, {"total": 0, "parse_ok": 0, "schema_ok": 0})
        outputs = event.get("outputs") or []

        if not isinstance(outputs, list):
            outputs = [outputs]

        for idx, output in enumerate(outputs):
            total_outputs += 1
            stats["total"] += 1

            payload, payload_text, parse_error = parse_json_payload(str(output))
            if parse_error:
                failures.append(
                    {
                        "line": line_no,
                        "schema": schema_name,
                        "stage": "parse",
                        "reason": parse_error,
                        "output_index": idx,
                        "snippet": str(output)[:500],
                    }
                )
                continue

            parse_ok += 1
            stats["parse_ok"] += 1

            ok, reason = validate_schema(schema_name, payload)
            if not ok:
                failures.append(
                    {
                        "line": line_no,
                        "schema": schema_name,
                        "stage": "schema",
                        "reason": reason,
                        "output_index": idx,
                        "snippet": str(payload_text)[:500] if payload_text else str(output)[:500],
                    }
                )
                continue

            schema_ok += 1
            stats["schema_ok"] += 1

    return {
        "trace_file": str(trace_path),
        "exists": True,
        "total_outputs": total_outputs,
        "parse_ok": parse_ok,
        "schema_ok": schema_ok,
        "parse_fail": total_outputs - parse_ok,
        "schema_fail": parse_ok - schema_ok,
        "failure_count": len(failures),
        "counts_by_schema": by_schema,
        "failures": failures[:100],
    }


def run_ragas(records: List[EvalRecord], output_dir: Path, trace_ragas_judge: bool = False) -> Dict[str, Any]:
    try:
        from datasets import Dataset
        from ragas import evaluate
        from ragas.llms import LangchainLLMWrapper
        from ragas.metrics import context_precision, context_recall, faithfulness
        from langchain_openai import ChatOpenAI
    except ImportError as exc:
        raise RuntimeError("RAGAS dependencies missing. Install requirements first.") from exc

    valid = [r for r in records if not r.error and r.answer.strip()]
    if not valid:
        raise RuntimeError("No valid records available for RAGAS")

    minimax_api_key = str(os.getenv("MINIMAX_API_KEY") or "").strip()
    minimax_base_url = str(os.getenv("MINIMAX_BASE_URL") or "https://api.minimaxi.com/v1").strip()
    minimax_model = str(os.getenv("MINIMAX_MODEL") or "MiniMax-M2.7").strip()
    if not minimax_api_key:
        raise RuntimeError("MINIMAX_API_KEY is empty. Please set it in .env.")

    has_ground_truth = all(bool(r.ground_truth.strip()) for r in valid)
    trace_writer: Optional[JsonlTraceWriter] = None
    trace_diag_path: Optional[Path] = None
    trace_path: Optional[Path] = None

    if trace_ragas_judge:
        trace_path = output_dir / "ragas_judge_trace.jsonl"
        trace_writer = JsonlTraceWriter(trace_path)

    ragas_rows: List[Dict[str, Any]] = []
    for row in valid:
        ragas_rows.append(
            {
                "question": row.question,
                "answer": row.answer,
                "contexts": row.contexts if row.contexts else [""],
                "ground_truth": row.ground_truth,
            }
        )

    class TracingLangchainLLMWrapper(LangchainLLMWrapper):
        def __init__(self, langchain_llm: Any, writer: Optional[JsonlTraceWriter]):
            super().__init__(langchain_llm)
            self._writer = writer

        def _record(
            self,
            prompt: Any,
            n: int,
            temperature: Optional[float],
            stop: Optional[List[str]],
            result: Any,
            mode: str,
        ) -> None:
            if self._writer is None:
                return

            try:
                prompt_text = prompt.to_string()
            except Exception:
                prompt_text = str(prompt)

            outputs: List[str] = []
            generations = getattr(result, "generations", None) or []
            if generations and isinstance(generations, list):
                first = generations[0]
                if isinstance(first, list):
                    outputs = [str(getattr(item, "text", "")) for item in first]

            model_name = str(
                getattr(self.langchain_llm, "model_name", None)
                or getattr(self.langchain_llm, "model", None)
                or ""
            )
            self._writer.append(
                {
                    "event_type": "judge_generation",
                    "mode": mode,
                    "model": model_name,
                    "n": n,
                    "temperature": temperature,
                    "stop": stop,
                    "expected_schema": classify_ragas_prompt(prompt_text),
                    "prompt": prompt_text,
                    "outputs": outputs,
                }
            )

        def generate_text(
            self,
            prompt: Any,
            n: int = 1,
            temperature: Optional[float] = None,
            stop: Optional[List[str]] = None,
            callbacks: Any = None,
        ) -> Any:
            result = super().generate_text(
                prompt=prompt,
                n=n,
                temperature=temperature,
                stop=stop,
                callbacks=callbacks,
            )
            self._record(prompt, n, temperature, stop, result, mode="sync")
            return result

        async def agenerate_text(
            self,
            prompt: Any,
            n: int = 1,
            temperature: Optional[float] = None,
            stop: Optional[List[str]] = None,
            callbacks: Any = None,
        ) -> Any:
            result = await super().agenerate_text(
                prompt=prompt,
                n=n,
                temperature=temperature,
                stop=stop,
                callbacks=callbacks,
            )
            self._record(prompt, n, temperature, stop, result, mode="async")
            return result

    judge_llm = ChatOpenAI(
        model=minimax_model,
        api_key=minimax_api_key,
        base_url=minimax_base_url,
        temperature=0,
    )
    ragas_llm = TracingLangchainLLMWrapper(judge_llm, trace_writer)

    metrics = [faithfulness]
    if has_ground_truth:
        metrics.extend([context_precision, context_recall])

    ds = Dataset.from_list(ragas_rows)
    result = evaluate(ds, metrics=metrics, llm=ragas_llm)
    df = result.to_pandas()

    csv_path = output_dir / "ragas_scores_per_sample.csv"
    df.to_csv(csv_path, index=False)

    numeric_columns = [
        col for col in df.columns if col not in {"question", "answer", "contexts", "ground_truth"} and str(df[col].dtype) != "object"
    ]

    overall_scores: Dict[str, float] = {}
    for col in numeric_columns:
        mean_val = float(df[col].mean(skipna=True))
        overall_scores[col] = round(mean_val, 6)

    if trace_path is not None:
        trace_diag = diagnose_judge_trace(trace_path)
        trace_diag_path = output_dir / "ragas_judge_diagnostics.json"
        write_json(trace_diag_path, trace_diag)

    summary = {
        "record_count": len(valid),
        "metrics": list(overall_scores.keys()),
        "scores": overall_scores,
        "has_ground_truth": has_ground_truth,
        "judge_provider": "minimax",
        "judge_model": minimax_model,
        "judge_base_url": minimax_base_url,
        "per_sample_csv": str(csv_path),
        "trace_ragas_judge": trace_ragas_judge,
    }
    if trace_path is not None:
        summary["judge_trace_jsonl"] = str(trace_path)
    if trace_diag_path is not None:
        summary["judge_trace_diagnostics_json"] = str(trace_diag_path)

    write_json(output_dir / "ragas_summary.json", summary)
    return summary


def main() -> int:
    load_project_env()
    args = parse_args()

    dataset_path = Path(args.dataset).expanduser().resolve()
    output_base = Path(args.output_dir).expanduser().resolve()
    output_base.mkdir(parents=True, exist_ok=True)
    run_dir = ensure_output_dir(output_base)

    try:
        samples = load_samples(dataset_path, default_provider=args.provider, default_user_id=args.user_id)
    except Exception as exc:
        print(f"[auto-ragas] Failed to load dataset: {exc}", file=sys.stderr)
        return 1

    print(f"[auto-ragas] Dataset rows: {len(samples)}")
    print(f"[auto-ragas] Base URL: {args.base_url}")
    print(f"[auto-ragas] Output dir: {run_dir}")

    records: List[EvalRecord] = []

    for idx, sample in enumerate(samples, start=1):
        session_id = f"{args.session_prefix}-{idx}-{uuid.uuid4().hex[:8]}"
        print(f"[auto-ragas] ({idx}/{len(samples)}) sample={sample.sample_id} session={session_id}")

        try:
            answer, events = stream_agent_answer(
                base_url=args.base_url,
                provider=sample.provider,
                user_id=sample.user_id,
                session_id=session_id,
                prompt=sample.question,
                timeout_seconds=args.timeout_seconds,
                tools=args.tools,
                tool_choice=args.tool_choice,
                verify_ssl=args.verify_ssl,
            )

            messages = fetch_session_messages(
                base_url=args.base_url,
                session_id=session_id,
                user_id=sample.user_id,
                timeout_seconds=args.timeout_seconds,
                verify_ssl=args.verify_ssl,
            )

            contexts, sources, search_call_count = extract_contexts_from_messages(messages)
            if not answer:
                answer = get_last_assistant_message(messages)

            records.append(
                EvalRecord(
                    sample_id=sample.sample_id,
                    question=sample.question,
                    ground_truth=sample.ground_truth,
                    answer=answer,
                    contexts=contexts,
                    sources=sources,
                    provider=sample.provider,
                    user_id=sample.user_id,
                    session_id=session_id,
                    stream_event_count=len(events),
                    search_call_count=search_call_count,
                )
            )
        except Exception as exc:
            records.append(
                EvalRecord(
                    sample_id=sample.sample_id,
                    question=sample.question,
                    ground_truth=sample.ground_truth,
                    answer="",
                    contexts=[],
                    sources=[],
                    provider=sample.provider,
                    user_id=sample.user_id,
                    session_id=session_id,
                    stream_event_count=0,
                    search_call_count=0,
                    error=str(exc),
                )
            )
            print(f"[auto-ragas] sample={sample.sample_id} failed: {exc}", file=sys.stderr)

    write_jsonl(
        run_dir / "samples_collected.jsonl",
        [
            {
                "id": r.sample_id,
                "question": r.question,
                "ground_truth": r.ground_truth,
                "answer": r.answer,
                "contexts": r.contexts,
                "sources": r.sources,
                "provider": r.provider,
                "user_id": r.user_id,
                "session_id": r.session_id,
                "stream_event_count": r.stream_event_count,
                "search_call_count": r.search_call_count,
                "error": r.error,
            }
            for r in records
        ],
    )

    meta = {
        "base_url": args.base_url,
        "dataset": str(dataset_path),
        "provider_default": args.provider,
        "tools": args.tools,
        "tool_choice": args.tool_choice,
        "timeout_seconds": args.timeout_seconds,
        "trace_ragas_judge": args.trace_ragas_judge,
        "total_samples": len(records),
        "failed_samples": sum(1 for r in records if r.error),
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    write_json(run_dir / "run_meta.json", meta)

    try:
        summary = run_ragas(records, run_dir, trace_ragas_judge=args.trace_ragas_judge)
    except Exception as exc:
        write_json(
            run_dir / "ragas_error.json",
            {
                "error": str(exc),
                "hint": "Check MINIMAX_API_KEY / MINIMAX_BASE_URL in .env and ragas dependencies.",
            },
        )
        print(f"[auto-ragas] RAGAS evaluation failed: {exc}", file=sys.stderr)
        print(f"[auto-ragas] Collected data still saved to: {run_dir}")
        return 2

    print("[auto-ragas] Done")
    print(f"[auto-ragas] Summary: {json.dumps(summary, ensure_ascii=False)}")
    print(f"[auto-ragas] Reports at: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
