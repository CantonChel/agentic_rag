import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agentic_rag_benchmark.console_server import ConsoleConfig
from agentic_rag_benchmark.env_loader import load_dotenv_defaults


class EnvLoaderTest(unittest.TestCase):
    def test_load_dotenv_defaults_reads_repo_env(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            env_path = root / ".env"
            anchor = root / "agentic_rag_benchmark" / "console_server.py"
            anchor.parent.mkdir(parents=True, exist_ok=True)
            anchor.write_text("# test anchor\n", encoding="utf-8")
            env_path.write_text('DEEPSEEK_API_KEY="repo-key"\n', encoding="utf-8")

            with patch("agentic_rag_benchmark.env_loader.Path.cwd", return_value=root):
                with patch.dict(os.environ, {}, clear=True):
                    loaded_path = load_dotenv_defaults(anchor)
                    self.assertEqual(loaded_path.resolve(), env_path.resolve())
                    self.assertEqual(os.environ["DEEPSEEK_API_KEY"], "repo-key")

    def test_load_dotenv_defaults_preserves_non_empty_environment_values(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            env_path = root / ".env"
            anchor = root / "agentic_rag_benchmark" / "console_server.py"
            anchor.parent.mkdir(parents=True, exist_ok=True)
            anchor.write_text("# test anchor\n", encoding="utf-8")
            env_path.write_text("DEEPSEEK_API_KEY=repo-key\n", encoding="utf-8")

            with patch("agentic_rag_benchmark.env_loader.Path.cwd", return_value=root):
                with patch.dict(os.environ, {"DEEPSEEK_API_KEY": "explicit-key"}, clear=True):
                    load_dotenv_defaults(anchor)
                    self.assertEqual(os.environ["DEEPSEEK_API_KEY"], "explicit-key")

    def test_load_dotenv_defaults_fills_blank_environment_values(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            env_path = root / ".env"
            anchor = root / "agentic_rag_benchmark" / "console_server.py"
            anchor.parent.mkdir(parents=True, exist_ok=True)
            anchor.write_text("# test anchor\n", encoding="utf-8")
            env_path.write_text("DEEPSEEK_API_KEY=repo-key\n", encoding="utf-8")

            with patch("agentic_rag_benchmark.env_loader.Path.cwd", return_value=root):
                with patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}, clear=True):
                    load_dotenv_defaults(anchor)
                    self.assertEqual(os.environ["DEEPSEEK_API_KEY"], "repo-key")

    def test_console_config_from_env_uses_repo_dotenv_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            benchmark_root = root / "agentic_rag_benchmark"
            benchmark_root.mkdir(parents=True, exist_ok=True)
            (root / ".env").write_text(
                "\n".join(
                    [
                        "BENCHMARK_CONSOLE_APP_BASE_URL=http://127.0.0.1:9001",
                        "DEEPSEEK_API_KEY=repo-key",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            with patch("agentic_rag_benchmark.env_loader.Path.cwd", return_value=root):
                with patch.dict(
                    os.environ,
                    {"BENCHMARK_CONSOLE_PACKAGE_ROOT": str(benchmark_root)},
                    clear=True,
                ):
                    config = ConsoleConfig.from_env()
                    self.assertEqual(config.app_base_url, "http://127.0.0.1:9001")
                    self.assertEqual(os.environ["DEEPSEEK_API_KEY"], "repo-key")


if __name__ == "__main__":
    unittest.main()
