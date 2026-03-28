from pathlib import Path

import unittest

from agentic_rag_benchmark.docreader_client import DocreaderReadResult
from agentic_rag_benchmark.evidence import EvidenceExtractor
from agentic_rag_benchmark.normalizer import DocumentNormalizer


class StubDocreaderClient:
    def __init__(self, result: DocreaderReadResult) -> None:
        self.result = result

    def read_path_file(self, source_path: Path, pipeline_version: str = "v1") -> DocreaderReadResult:
        return self.result


class EvidenceExtractorTest(unittest.TestCase):
    def test_extract_document_produces_stable_evidence_units(self) -> None:
        markdown = "\n".join(
            [
                "## Header Parameters",
                "",
                "| Name | Required |",
                "| ---- | -------- |",
                "| Authorization | Yes |",
                "",
                "## Responses",
                "",
                "- **code** (number)",
            ]
        )
        result = DocreaderReadResult(markdown_content=markdown, image_refs=[], metadata={}, error="")
        normalizer = DocumentNormalizer(StubDocreaderClient(result))
        document = normalizer.normalize_path(
            Path("/tmp/docs/api/获取设备属性.md"),
            source_root=Path("/tmp/docs"),
        )

        extractor = EvidenceExtractor()
        first = extractor.extract_document(document)
        second = extractor.extract_document(document)

        self.assertEqual(len(first), 2)
        self.assertEqual([item.evidence_id for item in first], [item.evidence_id for item in second])
        self.assertEqual(first[0].section_key, "header_parameters")
        self.assertEqual(first[0].anchor, "api/获取设备属性.md#header_parameters")
        self.assertEqual(first[1].section_key, "responses")


if __name__ == "__main__":
    unittest.main()
