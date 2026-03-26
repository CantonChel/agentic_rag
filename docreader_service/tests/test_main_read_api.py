import os
import tempfile

from fastapi.testclient import TestClient

from main import app


client = TestClient(app)


def test_read_endpoint_returns_whole_document_markdown():
    with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as tmp:
        tmp.write(b"hello sync docreader")
        path = tmp.name

    try:
        response = client.post(
            "/read",
            json={
                "jobId": "job-read-1",
                "knowledgeId": "knowledge-1",
                "fileUrl": path,
                "pipelineVersion": "v1",
                "options": {"user_id": "u-read"},
            },
        )
    finally:
        os.remove(path)

    assert response.status_code == 200
    data = response.json()
    assert data["markdownContent"] == "hello sync docreader"
    assert data["imageRefs"] == []
    assert data["metadata"]["user_id"] == "u-read"
    assert data["error"] == ""


def test_read_endpoint_returns_error_for_unsupported_file():
    response = client.post(
        "/read",
        json={
            "jobId": "job-read-bad",
            "knowledgeId": "knowledge-bad",
            "fileUrl": "/tmp/not-supported.unsupported",
            "pipelineVersion": "v1",
            "options": {},
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["markdownContent"] == ""
    assert data["imageRefs"] == []
    assert data["error"].startswith("unsupported_file:")
