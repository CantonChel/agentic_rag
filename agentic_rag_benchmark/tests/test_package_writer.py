import json
import tempfile
import unittest
from pathlib import Path

from agentic_rag_benchmark.contracts import BenchmarkSample
from agentic_rag_benchmark.contracts import EvidenceReference
from agentic_rag_benchmark.contracts import EvidenceUnit
from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.package_writer import PackageWriter


class PackageWriterTest(unittest.TestCase):
    def test_write_package_outputs_standard_files(self) -> None:
        evidence = EvidenceUnit(
            evidence_id="e1",
            doc_path="api/sample.md",
            section_key="header_parameters",
            section_title="Header Parameters",
            canonical_text="## Header Parameters\n\n| Name | Required |",
            anchor="api/sample.md#header_parameters",
            source_hash="hash",
            extractor_version="stage2_v1",
        )
        sample = BenchmarkSample(
            sample_id="sample_1",
            question="sample question",
            ground_truth="sample answer",
            ground_truth_contexts=[evidence.canonical_text],
            gold_evidence_refs=[EvidenceReference(evidence_id="e1", doc_path="api/sample.md", section_key="header_parameters")],
        )

        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = Path(tmp_dir) / "packages" / "api_docs" / "base_v1"
            result = PackageWriter().write_package(
                package_dir=package_dir,
                project_key="api_docs",
                suite_version="base_v1",
                evidence_units=[evidence],
                benchmark_samples=[sample],
            )

            self.assertEqual(result.evidence_count, 1)
            self.assertEqual(result.sample_count, 1)
            for file_name in STANDARD_PACKAGE_FILES.values():
                self.assertTrue((package_dir / file_name).exists())

            manifest = json.loads((package_dir / STANDARD_PACKAGE_FILES["suite_manifest"]).read_text(encoding="utf-8"))
            self.assertEqual(manifest["project_key"], "api_docs")
            self.assertEqual(manifest["suite_version"], "base_v1")


if __name__ == "__main__":
    unittest.main()
