"""Shared progress event helpers for benchmark workflows."""

from __future__ import annotations

from dataclasses import dataclass
from dataclasses import field
from typing import Any
from typing import Callable
from typing import Dict


@dataclass(frozen=True)
class ProgressEvent:
    """One structured progress update emitted by a benchmark workflow."""

    stage: str
    completed: int | None = None
    total: int | None = None
    message: str = ""
    details: Dict[str, Any] = field(default_factory=dict)


ProgressCallback = Callable[[ProgressEvent], None]


def emit_progress(
    callback: ProgressCallback | None,
    stage: str,
    completed: int | None = None,
    total: int | None = None,
    message: str = "",
    details: Dict[str, Any] | None = None,
) -> None:
    if callback is None:
        return
    callback(
        ProgressEvent(
            stage=stage,
            completed=completed,
            total=total,
            message=message,
            details=dict(details or {}),
        )
    )
