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


def run_ragas(records: List[EvalRecord], output_dir: Path) -> Dict[str, Any]:
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

    ragas_llm = LangchainLLMWrapper(
        ChatOpenAI(
            model=minimax_model,
            api_key=minimax_api_key,
            base_url=minimax_base_url,
            temperature=0,
        )
    )

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

    summary = {
        "record_count": len(valid),
        "metrics": list(overall_scores.keys()),
        "scores": overall_scores,
        "has_ground_truth": has_ground_truth,
        "judge_provider": "minimax",
        "judge_model": minimax_model,
        "judge_base_url": minimax_base_url,
        "per_sample_csv": str(csv_path),
    }
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
        "total_samples": len(records),
        "failed_samples": sum(1 for r in records if r.error),
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    write_json(run_dir / "run_meta.json", meta)

    try:
        summary = run_ragas(records, run_dir)
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
