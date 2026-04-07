import json
import tempfile
import unittest
from pathlib import Path

from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.validator import describe_schema
from agentic_rag_benchmark.validator import validate_legacy_dataset
from agentic_rag_benchmark.validator import validate_package_dir


class ValidatorTest(unittest.TestCase):
    def test_validate_package_dir_allows_empty_content_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = Path(tmp_dir)
            for file_name in STANDARD_PACKAGE_FILES.values():
                (package_dir / file_name).write_text("", encoding="utf-8")

            result = validate_package_dir(package_dir)

            self.assertTrue(result.ok)
            self.assertTrue(any("Package structure is valid" in message.message for message in result.messages))

    def test_validate_package_dir_checks_manifest_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = Path(tmp_dir)
            for file_name in STANDARD_PACKAGE_FILES.values():
                (package_dir / file_name).write_text("", encoding="utf-8")
            manifest = {
                "package_version": "v1",
                "project_key": "api_docs",
                "suite_version": "base_v1",
                "created_at": "2026-03-26T00:00:00Z",
                "generator_version": "stage1",
                "files": dict(STANDARD_PACKAGE_FILES),
            }
            (package_dir / STANDARD_PACKAGE_FILES["gold_package_manifest"]).write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

            result = validate_package_dir(package_dir)

            self.assertTrue(result.ok)
            self.assertTrue(any("matches gold_package_manifest" in message.message for message in result.messages))

    def test_validate_legacy_dataset_success(self) -> None:
        dataset_path = Path(__file__).resolve().parents[1] / "fixtures" / "legacy" / "legacy_question_bank_sample.jsonl"
        result = validate_legacy_dataset(dataset_path)
        self.assertTrue(result.ok)

    def test_describe_schema_lists_required_fields(self) -> None:
        result = describe_schema("benchmark_sample")
        self.assertTrue(result.ok)
        self.assertTrue(any("sample_id" in message.message for message in result.messages))


if __name__ == "__main__":
    unittest.main()
