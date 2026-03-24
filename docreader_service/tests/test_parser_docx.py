import asyncio
import os
import tempfile
import unittest
from unittest import mock

from parser import _extract_text
from parser import parse_to_chunks


class ParserDocxTest(unittest.TestCase):
    @mock.patch("parser.convert_docx_to_markdown", return_value="# title\n\nhello markdown")
    def test_extract_text_from_docx(self, mocked_convert: mock.MagicMock) -> None:
        text = _extract_text(b"fake-docx-bytes", ".docx")
        mocked_convert.assert_called_once_with(b"fake-docx-bytes")
        self.assertEqual("# title\n\nhello markdown", text)

    @mock.patch("parser.convert_docx_to_markdown", return_value="# h1\n\nbody line")
    def test_parse_to_chunks_from_local_docx_file(self, _mocked_convert: mock.MagicMock) -> None:
        with tempfile.NamedTemporaryFile(suffix=".docx", delete=False) as tmp:
            tmp.write(b"fake-docx")
            path = tmp.name

        try:
            chunks = asyncio.run(
                parse_to_chunks(
                    job_id="job-docx-1",
                    file_url=path,
                    options={"chunk_size": 30, "chunk_overlap": 5, "user_id": "u-1"},
                )
            )
        finally:
            os.remove(path)

        self.assertTrue(chunks)
        self.assertEqual(".docx", chunks[0].metadata.get("source_ext"))
        self.assertEqual("u-1", chunks[0].metadata.get("user_id"))
        self.assertIn("# h1", chunks[0].content)


if __name__ == "__main__":
    unittest.main()
