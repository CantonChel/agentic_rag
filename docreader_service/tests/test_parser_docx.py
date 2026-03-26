import asyncio
import os
import tempfile
import unittest
from unittest import mock

from parser import _build_markdown_with_image_mapping
from parser import _compose_markdown_pages_with_images
from parser import _extract_markdown_data_uri_images
from parser import _extract_text
from parser import read_document
from parser import read_document_safe


class ParserDocxTest(unittest.TestCase):
    @mock.patch("parser.convert_docx_to_markdown", return_value="# title\n\nhello markdown")
    def test_extract_text_from_docx(self, mocked_convert: mock.MagicMock) -> None:
        text = _extract_text(b"fake-docx-bytes", ".docx")
        mocked_convert.assert_called_once_with(b"fake-docx-bytes")
        self.assertEqual("# title\n\nhello markdown", text)

    @mock.patch("parser.convert_docx_to_markdown", return_value="# h1\n\nbody line")
    def test_read_document_from_local_docx_file(self, _mocked_convert: mock.MagicMock) -> None:
        with tempfile.NamedTemporaryFile(suffix=".docx", delete=False) as tmp:
            tmp.write(b"fake-docx")
            path = tmp.name

        try:
            result = asyncio.run(
                read_document(
                    job_id="job-docx-1",
                    file_url=path,
                    options={"user_id": "u-1"},
                )
            )
        finally:
            os.remove(path)

        self.assertEqual(".docx", result.metadata.get("source_ext"))
        self.assertEqual("u-1", result.metadata.get("user_id"))
        self.assertIn("# h1", result.markdown_content)
        self.assertEqual("", result.error)

    def test_read_document_from_local_txt_file_should_return_whole_markdown(self) -> None:
        long_text = "A" * 1200
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as tmp:
            tmp.write(long_text.encode("utf-8"))
            path = tmp.name

        try:
            result = asyncio.run(
                read_document(
                    job_id="job-txt-1",
                    file_url=path,
                    options={"user_id": "u-2"},
                )
            )
        finally:
            os.remove(path)

        self.assertEqual(".txt", result.metadata.get("source_ext"))
        self.assertEqual("u-2", result.metadata.get("user_id"))
        self.assertEqual(long_text, result.markdown_content)
        self.assertEqual([], result.image_refs)

    def test_read_document_safe_returns_error_string_for_unsupported_file(self) -> None:
        result = asyncio.run(
            read_document_safe(
                job_id="job-bad-1",
                file_url="/tmp/not-found.unsupported",
                options={},
            )
        )
        self.assertEqual("", result.markdown_content)
        self.assertTrue(result.error.startswith("unsupported_file:"))

    def test_extract_markdown_data_uri_images(self) -> None:
        source = "head\n\n![](data:image/png;base64,aGVsbG8=)\n\ntail"
        replaced, refs = _extract_markdown_data_uri_images(source)
        self.assertIn("images/", replaced)
        self.assertEqual(1, len(refs))
        self.assertEqual(b"hello", refs[0][1])
        self.assertEqual("png", refs[0][2])

    def test_extract_markdown_data_uri_images_with_angle_brackets(self) -> None:
        source = "![cap](<data:image/jpeg;base64,aGVsbG8=>)"
        replaced, refs = _extract_markdown_data_uri_images(source)
        self.assertIn("images/", replaced)
        self.assertEqual(1, len(refs))
        self.assertEqual("jpeg", refs[0][2])

    def test_extract_html_data_uri_images(self) -> None:
        source = '<p>x</p><img alt="demo" src="data:image/webp;base64,aGVsbG8=" /><p>y</p>'
        replaced, refs = _extract_markdown_data_uri_images(source)
        self.assertIn("images/", replaced)
        self.assertEqual(1, len(refs))
        self.assertEqual(b"hello", refs[0][1])
        self.assertEqual("webp", refs[0][2])

    def test_build_markdown_with_image_mapping(self) -> None:
        sequence = [("text", "段落1"), ("image", "__DOCX_IMAGE_0__"), ("text", "段落2")]
        mapping = {"__DOCX_IMAGE_0__": "images/demo.png"}
        markdown = _build_markdown_with_image_mapping(sequence, mapping)
        self.assertIn("段落1", markdown)
        self.assertIn("![](images/demo.png)", markdown)
        self.assertIn("段落2", markdown)

    def test_compose_markdown_pages_with_images(self) -> None:
        markdown, refs = _compose_markdown_pages_with_images(
            ["p1 text", "p2 text"],
            [[(b"img1", "png")], [(b"img2", "jpg"), (b"img3", "png")]],
        )
        self.assertEqual(3, len(refs))
        self.assertIn("p1 text", markdown)
        self.assertIn("p2 text", markdown)
        self.assertGreaterEqual(markdown.count("![](images/"), 3)

    def test_compose_markdown_pages_without_images(self) -> None:
        markdown, refs = _compose_markdown_pages_with_images(["a", "b"], [])
        self.assertEqual([], refs)
        self.assertIn("a", markdown)
        self.assertIn("b", markdown)

if __name__ == "__main__":
    unittest.main()
