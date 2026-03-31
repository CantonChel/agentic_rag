"""Lightweight .env loading for benchmark entrypoints."""

from __future__ import annotations

import os
import re
from pathlib import Path


_DOTENV_LINE_RE = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$")


def load_dotenv_defaults(anchor: Path | None = None) -> Path | None:
    """Load the nearest .env into os.environ without overriding non-empty values."""

    for candidate in iter_dotenv_candidates(anchor):
        if not candidate.exists() or not candidate.is_file():
            continue
        for key, value in parse_dotenv_file(candidate).items():
            if str(os.environ.get(key) or "").strip():
                continue
            os.environ[key] = value
        return candidate
    return None


def iter_dotenv_candidates(anchor: Path | None = None) -> list[Path]:
    seen = set()
    candidates: list[Path] = []
    for start in (Path.cwd(), anchor):
        if start is None:
            continue
        current = start.expanduser().resolve()
        if current.is_file():
            current = current.parent
        for parent in (current, *current.parents):
            candidate = parent / ".env"
            marker = str(candidate)
            if marker in seen:
                continue
            seen.add(marker)
            candidates.append(candidate)
    return candidates


def parse_dotenv_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parsed = parse_dotenv_line(line)
        if parsed is None:
            continue
        key, value = parsed
        values[key] = value
    return values


def parse_dotenv_line(line: str) -> tuple[str, str] | None:
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        return None
    match = _DOTENV_LINE_RE.match(line)
    if not match:
        return None
    key = match.group(1)
    value = normalize_dotenv_value(match.group(2))
    return key, value


def normalize_dotenv_value(raw_value: str) -> str:
    text = raw_value.strip()
    if not text:
        return ""
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {'"', "'"}:
        unquoted = text[1:-1]
        if text[0] == '"':
            return bytes(unquoted, "utf-8").decode("unicode_escape")
        return unquoted
    comment_index = text.find(" #")
    if comment_index >= 0:
        text = text[:comment_index].rstrip()
    return text
