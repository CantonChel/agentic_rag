from pathlib import Path

import unittest

from agentic_rag_benchmark.docreader_client import DocreaderReadResult
from agentic_rag_benchmark.normalizer import DocumentNormalizer
from agentic_rag_benchmark.normalizer import sanitize_markdown


class StubDocreaderClient:
    def __init__(self, result: DocreaderReadResult) -> None:
        self.result = result

    def read_path_file(self, source_path: Path, pipeline_version: str = "v1") -> DocreaderReadResult:
        return self.result


class NormalizerTest(unittest.TestCase):
    def test_sanitize_markdown_normalizes_line_endings(self) -> None:
        raw = "abc\r\n\r\n# Title  \r\nbody\x00"
        self.assertEqual(sanitize_markdown(raw), "abc\n\n# Title\nbody")

    def test_normalize_docreader_result_splits_markdown_into_blocks(self) -> None:
        markdown = "\n".join(
            [
                "Intro paragraph.",
                "",
                "## Header Parameters",
                "",
                "| Name | Required |",
                "| ---- | -------- |",
                "| X-Isp-Code | Yes |",
                "",
                "## Request Body",
                "",
                "- **deviceId** (string)",
            ]
        )
        result = DocreaderReadResult(markdown_content=markdown, image_refs=[], metadata={"source_ext": ".md"}, error="")
        normalizer = DocumentNormalizer(StubDocreaderClient(result))

        normalized = normalizer.normalize_path(
            Path("/tmp/docs/device/修改设备属性.md"),
            source_root=Path("/tmp/docs"),
        )

        self.assertEqual(normalized.doc_path, "device/修改设备属性.md")
        self.assertEqual(normalized.title, "修改设备属性")
        self.assertEqual(len(normalized.blocks), 3)
        self.assertEqual(normalized.blocks[0].section_key, "root")
        self.assertEqual(normalized.blocks[1].section_key, "header_parameters")
        self.assertEqual(normalized.blocks[1].block_type, "header_section")
        self.assertEqual(normalized.blocks[2].section_key, "request_body")
        self.assertEqual(normalized.blocks[2].block_type, "request_section")
        self.assertIn("source_name", normalized.metadata)


if __name__ == "__main__":
    unittest.main()
