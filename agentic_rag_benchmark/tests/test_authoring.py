import unittest

from agentic_rag_benchmark.authoring import BenchmarkAuthoringStrategy
from agentic_rag_benchmark.contracts import AuthoringBlock


class BenchmarkAuthoringStrategyTest(unittest.TestCase):
    def test_generate_required_field_sample_from_header_section(self) -> None:
        block = AuthoringBlock(
            block_id="block-1",
            doc_path="api/修改设备属性.md",
            section_key="header_parameters",
            section_title="Header Parameters",
            block_type="header_section",
            heading_level=2,
            text="\n".join(
                [
                    "## Header Parameters",
                    "",
                    "| Name | Required |",
                    "| ---- | -------- |",
                    "| Authorization | Yes |",
                    "| X-Isp-Code | Yes |",
                ]
            ),
            anchor="api/修改设备属性.md#header_parameters",
            source_hash="hash",
            start_line=3,
            end_line=8,
        )

        sample = BenchmarkAuthoringStrategy().generate_sample(block)

        self.assertIn("必填请求头有哪些", sample.question)
        self.assertEqual(sample.ground_truth, "必填请求头有：Authorization、X-Isp-Code。")
        self.assertEqual(sample.gold_block_refs[0].block_id, "block-1")

    def test_generate_field_list_sample_from_response_section(self) -> None:
        block = AuthoringBlock(
            block_id="block-2",
            doc_path="api/获取设备属性.md",
            section_key="responses",
            section_title="Responses",
            block_type="response_section",
            heading_level=2,
            text="\n".join(
                [
                    "## Responses",
                    "",
                    "- **code** (number)",
                    "- **data** (object)",
                    "- **message** (string)",
                ]
            ),
            anchor="api/获取设备属性.md#responses",
            source_hash="hash",
            start_line=1,
            end_line=5,
        )

        sample = BenchmarkAuthoringStrategy().generate_sample(block)

        self.assertIn("返回字段有哪些", sample.question)
        self.assertEqual(sample.ground_truth, "返回字段包括：code、data、message。")

    def test_generate_summary_sample_for_generic_section(self) -> None:
        block = AuthoringBlock(
            block_id="block-3",
            doc_path="manual/产品说明.md",
            section_key="overview",
            section_title="Overview",
            block_type="section",
            heading_level=2,
            text="## Overview\n\n这是一个面向展厅场景的设备控制平台，可以统一管理内容和终端。",
            anchor="manual/产品说明.md#overview",
            source_hash="hash",
            start_line=1,
            end_line=3,
        )

        sample = BenchmarkAuthoringStrategy().generate_sample(block)

        self.assertIn("主要说明了什么", sample.question)
        self.assertIn("设备控制平台", sample.ground_truth)


if __name__ == "__main__":
    unittest.main()
