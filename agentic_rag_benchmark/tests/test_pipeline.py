import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agentic_rag_benchmark.docreader_client import DocreaderReadResult
from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.pipeline import build_benchmark_package
from agentic_rag_benchmark.validator import validate_package_dir


class BuildBenchmarkPackageTest(unittest.TestCase):
    def test_build_benchmark_package_writes_gold_package(self) -> None:
        markdown = "\n".join(
            [
                "# 修改设备属性",
                "",
                "## Header Parameters",
                "",
                "| Name | Required |",
                "| ---- | -------- |",
                "| Authorization | Yes |",
                "| X-Isp-Code | Yes |",
            ]
        )
        docreader_result = DocreaderReadResult(
            markdown_content=markdown,
            image_refs=[],
            metadata={"source_ext": ".md"},
            error="",
        )

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            source_root = tmp_path / "docs"
            source_root.mkdir(parents=True, exist_ok=True)
            source_file = source_root / "api.md"
            source_file.write_text("# placeholder\n", encoding="utf-8")

            with patch(
                "agentic_rag_benchmark.docreader_client.DocreaderServiceClient.read_path_file",
                return_value=docreader_result,
            ):
                report = build_benchmark_package(
                    source_path=source_root,
                    project_key="API Docs",
                    suite_version="base_v1",
                    package_root=tmp_path,
                    docreader_base_url="http://127.0.0.1:8090",
                    source_root=source_root,
                )

            self.assertEqual(report.package_dir, tmp_path / "packages" / "api_docs" / "base_v1")
            self.assertEqual(report.normalized_document_count, 1)
            self.assertEqual(report.authoring_block_count, 2)
            self.assertEqual(report.sample_count, 2)

            for file_name in STANDARD_PACKAGE_FILES.values():
                self.assertTrue((report.package_dir / file_name).exists())

            samples = [
                json.loads(line)
                for line in (report.package_dir / STANDARD_PACKAGE_FILES["samples"]).read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            self.assertTrue(any(sample["gold_block_refs"][0]["doc_path"] == "api.md" for sample in samples))
            self.assertTrue(any("必填请求头有哪些" in sample["question"] for sample in samples))

            validation_result = validate_package_dir(report.package_dir)
            self.assertTrue(validation_result.ok)


if __name__ == "__main__":
    unittest.main()
