import os
from dataclasses import dataclass


@dataclass
class Settings:
    host: str = os.getenv("DOCREADER_HOST", "0.0.0.0")
    port: int = int(os.getenv("DOCREADER_PORT", "8090"))
    worker_count: int = int(os.getenv("DOCREADER_WORKER_COUNT", "2"))
    callback_timeout_seconds: float = float(os.getenv("DOCREADER_CALLBACK_TIMEOUT_SECONDS", "120"))
    callback_retry_max: int = int(os.getenv("DOCREADER_CALLBACK_RETRY_MAX", "3"))
    callback_secret: str = os.getenv("DOCREADER_CALLBACK_SECRET", "")
    signature_header: str = os.getenv("DOCREADER_SIGNATURE_HEADER", "X-Docreader-Signature")
    timestamp_header: str = os.getenv("DOCREADER_TIMESTAMP_HEADER", "X-Docreader-Timestamp")
    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "localhost:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    minio_bucket: str = os.getenv("MINIO_BUCKET", "agentic-rag")
    minio_secure: bool = os.getenv("MINIO_SECURE", "false").lower() == "true"


settings = Settings()
