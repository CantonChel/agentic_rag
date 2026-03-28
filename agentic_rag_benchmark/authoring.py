"""Rule-based benchmark sample authoring from evidence units."""

from __future__ import annotations

from pathlib import Path
from typing import Dict
from typing import Iterable
from typing import List
from typing import Sequence
import hashlib
import re

from .contracts import BenchmarkSample
from .contracts import EvidenceReference
from .contracts import EvidenceUnit


FIELD_PATTERN = re.compile(r"^\s*-\s+\*\*(.+?)\*\*")
TABLE_LINE_PATTERN = re.compile(r"^\s*\|.*\|\s*$")
ALIGNMENT_LINE_PATTERN = re.compile(r"^\s*\|?[\s:\-|\+]+\|?\s*$")


class BenchmarkAuthoringStrategy:
    """Create portable benchmark samples from evidence units."""

    def __init__(self, suite_version: str = "base_v1") -> None:
        self.suite_version = suite_version

    def generate_samples(self, evidence_units: Iterable[EvidenceUnit]) -> List[BenchmarkSample]:
        samples: List[BenchmarkSample] = []
        for evidence in evidence_units:
            samples.append(self.generate_sample(evidence))
        return samples

    def generate_sample(self, evidence: EvidenceUnit) -> BenchmarkSample:
        doc_title = Path(evidence.doc_path).stem
        subject = infer_subject(evidence.section_title)
        required_fields, all_fields = extract_fields(evidence.canonical_text)

        if subject and required_fields:
            question = f"{doc_title}接口的必填{subject}有哪些？"
            ground_truth = f"必填{subject}有：{join_items(required_fields)}。"
            sample_kind = "required_fields"
            difficulty = "easy"
            tags = ["structured", subject]
        elif subject and all_fields:
            question = f"{doc_title}接口的{subject}有哪些？"
            ground_truth = f"{subject}包括：{join_items(all_fields)}。"
            sample_kind = "field_list"
            difficulty = "easy"
            tags = ["structured", subject]
        else:
            question = f"{doc_title}文档中“{evidence.section_title}”部分主要说明了什么？"
            ground_truth = summarize_text(evidence.canonical_text)
            sample_kind = "summary"
            difficulty = "medium"
            tags = ["summary", evidence.section_key]

        return BenchmarkSample(
            sample_id=build_sample_id(evidence, sample_kind),
            question=question,
            ground_truth=ground_truth,
            ground_truth_contexts=[evidence.canonical_text],
            gold_evidence_refs=[
                EvidenceReference(
                    evidence_id=evidence.evidence_id,
                    doc_path=evidence.doc_path,
                    section_key=evidence.section_key,
                )
            ],
            tags=tags,
            difficulty=difficulty,
            suite_version=self.suite_version,
        )


def infer_subject(section_title: str) -> str:
    lowered = section_title.strip().lower()
    if "header" in lowered or "请求头" in section_title:
        return "请求头"
    if "query" in lowered or "查询参数" in section_title:
        return "查询参数"
    if "request body" in lowered or "请求体" in section_title or "body" == lowered:
        return "请求体字段"
    if "response" in lowered or "返回" in section_title:
        return "返回字段"
    return ""


def extract_fields(text: str) -> tuple[List[str], List[str]]:
    required_fields, all_fields = extract_fields_from_tables(text)
    if all_fields:
        return dedupe(required_fields), dedupe(all_fields)

    bullet_fields = extract_fields_from_bullets(text)
    return [], dedupe(bullet_fields)


def extract_fields_from_tables(text: str) -> tuple[List[str], List[str]]:
    required_fields: List[str] = []
    all_fields: List[str] = []

    lines = text.splitlines()
    index = 0
    while index < len(lines):
        if not TABLE_LINE_PATTERN.match(lines[index]):
            index += 1
            continue

        table_lines = [lines[index]]
        index += 1
        while index < len(lines) and TABLE_LINE_PATTERN.match(lines[index]):
            table_lines.append(lines[index])
            index += 1

        if len(table_lines) < 2:
            continue

        rows = [parse_table_line(line) for line in table_lines]
        if len(rows) < 2 or not ALIGNMENT_LINE_PATTERN.match(table_lines[1]):
            continue

        headers = [cell.strip().lower() for cell in rows[0]]
        name_idx = pick_header_index(headers, ("name", "字段", "参数", "property"))
        required_idx = pick_header_index(headers, ("required", "必填"))
        if name_idx is None:
            continue

        for row in rows[2:]:
            if len(row) <= name_idx:
                continue
            field_name = clean_field_name(row[name_idx])
            if not field_name:
                continue
            all_fields.append(field_name)
            if required_idx is not None and len(row) > required_idx and is_required_value(row[required_idx]):
                required_fields.append(field_name)

    return required_fields, all_fields


def extract_fields_from_bullets(text: str) -> List[str]:
    fields: List[str] = []
    for line in text.splitlines():
        match = FIELD_PATTERN.match(line)
        if not match:
            continue
        field_name = clean_field_name(match.group(1))
        if field_name:
            fields.append(field_name)
    return fields


def parse_table_line(line: str) -> List[str]:
    stripped = line.strip().strip("|")
    return [cell.strip() for cell in stripped.split("|")]


def pick_header_index(headers: Sequence[str], candidates: Sequence[str]) -> int | None:
    for index, header in enumerate(headers):
        for candidate in candidates:
            if candidate in header:
                return index
    return None


def clean_field_name(value: str) -> str:
    text = str(value or "").strip()
    text = re.sub(r"\s+", " ", text)
    return text


def is_required_value(value: str) -> bool:
    lowered = str(value or "").strip().lower()
    return lowered in {"yes", "true", "required", "是", "必填"}


def dedupe(items: Iterable[str]) -> List[str]:
    out: List[str] = []
    seen = set()
    for item in items:
        if not item or item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


def join_items(items: Sequence[str]) -> str:
    return "、".join(items)


def summarize_text(text: str, max_chars: int = 160) -> str:
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("#") or TABLE_LINE_PATTERN.match(stripped):
            continue
        lines.append(stripped)
    summary = " ".join(lines).strip()
    if not summary:
        summary = text.strip()
    summary = re.sub(r"\s+", " ", summary)
    if len(summary) > max_chars:
        summary = summary[: max_chars - 1].rstrip() + "…"
    if summary and summary[-1] not in "。！？.!?":
        summary += "。"
    return summary


def build_sample_id(evidence: EvidenceUnit, sample_kind: str) -> str:
    seed = "|".join([evidence.evidence_id, sample_kind])
    digest = hashlib.sha1(seed.encode("utf-8")).hexdigest()[:12]
    return f"{evidence.section_key}_{sample_kind}_{digest}"
