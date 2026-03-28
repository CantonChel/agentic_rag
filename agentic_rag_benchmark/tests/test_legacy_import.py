from pathlib import Path

import unittest

from agentic_rag_benchmark.legacy_import import load_legacy_dataset


class LegacyImportTest(unittest.TestCase):
    def test_load_legacy_dataset(self) -> None:
        dataset_path = Path(__file__).resolve().parents[1] / "fixtures" / "legacy" / "legacy_question_bank_sample.jsonl"
        samples = load_legacy_dataset(dataset_path)

        self.assertEqual(len(samples), 1)
        sample = samples[0]
        self.assertEqual(sample.question, "该接口的必填请求头有哪些？")
        self.assertEqual(sample.ground_truth, "必填请求头包括 X-Isp-Code、Authorization 和 X-Isp-TraceId。")
        self.assertEqual(sample.suite_version, "legacy_import_v1")
        self.assertEqual(len(sample.gold_evidence_refs), 1)
        self.assertTrue(sample.gold_evidence_refs[0].evidence_id.startswith("legacy_"))
        self.assertEqual(sample.gold_evidence_refs[0].doc_path, "设备管理/获取设备详情.md")


if __name__ == "__main__":
    unittest.main()
