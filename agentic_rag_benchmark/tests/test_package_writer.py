import json
import tempfile
import unittest
from pathlib import Path

from agentic_rag_benchmark.contracts import AuthoringBlock
from agentic_rag_benchmark.contracts import BenchmarkSample
from agentic_rag_benchmark.contracts import BlockLink
from agentic_rag_benchmark.contracts import GoldBlockReference
from agentic_rag_benchmark.contracts import SampleGenerationTrace
from agentic_rag_benchmark.contracts import SourceFileRecord
from agentic_rag_benchmark.contracts import SourceManifest
from agentic_rag_benchmark.normalizer import NormalizedBlock
from agentic_rag_benchmark.normalizer import NormalizedDocument
from agentic_rag_benchmark.package_spec import STANDARD_PACKAGE_FILES
from agentic_rag_benchmark.package_writer import PackageWriter
from agentic_rag_benchmark.validator import validate_package_dir


class PackageWriterTest(unittest.TestCase):
    def test_write_package_outputs_gold_package_files(self) -> None:
        source_manifest = SourceManifest(
            source_set_id="source-set-1",
            project_key="api_docs",
            source_root="/tmp/source",
            file_count=1,
            files=[
                SourceFileRecord(
                    path="api/sample.md",
                    size_bytes=128,
                    sha256="filehash",
                )
            ],
            created_at="2026-04-07T00:00:00Z",
        )
        document = NormalizedDocument(
            doc_path="api/sample.md",
            title="sample",
            normalized_text="## Header Parameters\n\n| Name | Required |",
            metadata={"source_name": "sample.md"},
            blocks=[
                NormalizedBlock(
                    block_id="block-1",
                    section_key="header_parameters",
                    section_title="Header Parameters",
                    block_type="header_section",
                    heading_level=2,
                    content="## Header Parameters\n\n| Name | Required |",
                    start_line=1,
                    end_line=3,
                )
            ],
        )
        block = AuthoringBlock(
            block_id="block-1",
            doc_path="api/sample.md",
            section_key="header_parameters",
            section_title="Header Parameters",
            block_type="header_section",
            heading_level=2,
            text="## Header Parameters\n\n| Name | Required |",
            anchor="api/sample.md#header_parameters",
            source_hash="hash",
            start_line=1,
            end_line=3,
        )
        block_link = BlockLink(
            from_block_id="root",
            to_block_id="block-1",
            link_type="parent_child",
        )
        sample = BenchmarkSample(
            sample_id="sample_1",
            question="sample question",
            ground_truth="sample answer",
            ground_truth_contexts=[block.text],
            gold_block_refs=[
                GoldBlockReference(
                    block_id="block-1",
                    doc_path="api/sample.md",
                    section_key="header_parameters",
                )
            ],
        )
        sample_trace = SampleGenerationTrace(
            sample_id="sample_1",
            generation_method="rule_based",
            input_block_ids=["block-1"],
            generator_version="gold_stage1_v1",
            model_or_rule_name="BenchmarkAuthoringStrategy",
            validation_status="generated",
        )

        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = Path(tmp_dir) / "packages" / "api_docs" / "base_v1"
            result = PackageWriter().write_package(
                package_dir=package_dir,
                project_key="api_docs",
                suite_version="base_v1",
                source_manifest=source_manifest,
                normalized_documents=[document],
                authoring_blocks=[block],
                block_links=[block_link],
                benchmark_samples=[sample],
                sample_generation_traces=[sample_trace],
            )

            self.assertEqual(result.authoring_block_count, 1)
            self.assertEqual(result.sample_count, 1)
            for file_name in STANDARD_PACKAGE_FILES.values():
                self.assertTrue((package_dir / file_name).exists())

            manifest = json.loads((package_dir / STANDARD_PACKAGE_FILES["gold_package_manifest"]).read_text(encoding="utf-8"))
            self.assertEqual(manifest["project_key"], "api_docs")
            self.assertEqual(manifest["suite_version"], "base_v1")
            self.assertEqual(manifest["files"], dict(STANDARD_PACKAGE_FILES))
            samples = [
                json.loads(line)
                for line in (package_dir / STANDARD_PACKAGE_FILES["samples"]).read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            self.assertIn("gold_block_refs", samples[0])
            self.assertNotIn("gold_evidence_refs", samples[0])
            self.assertEqual(samples[0]["gold_block_refs"][0]["block_id"], "block-1")
            blocks = [
                json.loads(line)
                for line in (package_dir / STANDARD_PACKAGE_FILES["authoring_blocks"]).read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            self.assertEqual(blocks[0]["block_id"], "block-1")
            referenced_block_ids = {ref["block_id"] for sample_row in samples for ref in sample_row["gold_block_refs"]}
            authoring_block_ids = {block_row["block_id"] for block_row in blocks}
            self.assertTrue(referenced_block_ids.issubset(authoring_block_ids))
            review_markdown = (package_dir / STANDARD_PACKAGE_FILES["review_markdown"]).read_text(encoding="utf-8")
            self.assertIn("Gold Package Review", review_markdown)
            self.assertIn("sample question", review_markdown)
            self.assertIn("block-1", review_markdown)
            self.assertIn(block.text, review_markdown)

            validation_result = validate_package_dir(package_dir)
            self.assertTrue(validation_result.ok)


if __name__ == "__main__":
    unittest.main()
