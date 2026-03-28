import unittest

from agentic_rag_benchmark.cli.__main__ import build_parser


class RunnerCliTest(unittest.TestCase):
    def test_run_benchmark_parser_supports_stage6_defaults(self) -> None:
        parser = build_parser()

        args = parser.parse_args(
            [
                "run-benchmark",
                "--package-dir",
                "/tmp/packages/api_docs/base_v1",
                "--provider",
                "openai",
                "--build-id",
                "build_123",
                "--user-id",
                "bench-user",
            ]
        )

        self.assertEqual(args.command, "run-benchmark")
        self.assertEqual(args.package_dir, "/tmp/packages/api_docs/base_v1")
        self.assertEqual(args.provider, "openai")
        self.assertEqual(args.build_id, "build_123")
        self.assertEqual(args.user_id, "bench-user")
        self.assertEqual(args.session_prefix, "benchmark")
        self.assertEqual(args.timeout_seconds, 180)
        self.assertFalse(args.verify_ssl)

    def test_run_benchmark_parser_supports_legacy_dataset_mode(self) -> None:
        parser = build_parser()

        args = parser.parse_args(
            [
                "run-benchmark",
                "--legacy-dataset",
                "/tmp/legacy/questions.jsonl",
                "--provider",
                "openai",
                "--build-id",
                "build_123",
                "--user-id",
                "bench-user",
            ]
        )

        self.assertEqual(args.command, "run-benchmark")
        self.assertIsNone(args.package_dir)
        self.assertEqual(args.legacy_dataset, "/tmp/legacy/questions.jsonl")


if __name__ == "__main__":
    unittest.main()
