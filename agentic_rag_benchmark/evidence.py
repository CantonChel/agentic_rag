"""Evidence extraction from normalized documents."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable
from typing import List
import hashlib

from .contracts import EvidenceUnit
from .normalizer import NormalizedBlock
from .normalizer import NormalizedDocument


DEFAULT_EXTRACTOR_VERSION = "stage2_v1"


@dataclass(frozen=True)
class ExtractedEvidenceBatch:
    document: NormalizedDocument
    evidence_units: List[EvidenceUnit]


class EvidenceExtractor:
    """Convert normalized section blocks into stable evidence units."""

    def __init__(self, extractor_version: str = DEFAULT_EXTRACTOR_VERSION, min_content_chars: int = 10) -> None:
        self.extractor_version = extractor_version
        self.min_content_chars = min_content_chars

    def extract_document(self, document: NormalizedDocument) -> List[EvidenceUnit]:
        evidence_units: List[EvidenceUnit] = []
        for block in document.blocks:
            if not should_emit_block(block, self.min_content_chars):
                continue
            canonical_text = block.content.strip()
            source_hash = stable_source_hash(canonical_text)
            evidence_units.append(
                EvidenceUnit(
                    evidence_id=stable_evidence_id(document.doc_path, block.section_key, source_hash, self.extractor_version),
                    doc_path=document.doc_path,
                    section_key=block.section_key,
                    section_title=block.section_title,
                    canonical_text=canonical_text,
                    anchor=build_anchor(document.doc_path, block.section_key),
                    source_hash=source_hash,
                    extractor_version=self.extractor_version,
                )
            )
        return evidence_units

    def extract_documents(self, documents: Iterable[NormalizedDocument]) -> List[ExtractedEvidenceBatch]:
        return [
            ExtractedEvidenceBatch(document=document, evidence_units=self.extract_document(document))
            for document in documents
        ]


def should_emit_block(block: NormalizedBlock, min_content_chars: int) -> bool:
    content = block.content.strip()
    if not content:
        return False
    if len(content) < min_content_chars:
        return False
    return True


def stable_source_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def stable_evidence_id(doc_path: str, section_key: str, source_hash: str, extractor_version: str) -> str:
    seed = "|".join([doc_path, section_key, source_hash, extractor_version])
    return hashlib.sha1(seed.encode("utf-8")).hexdigest()[:24]


def build_anchor(doc_path: str, section_key: str) -> str:
    return f"{doc_path}#{section_key}"
