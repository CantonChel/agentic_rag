import os
from dataclasses import dataclass


@dataclass
class Settings:
    host: str = os.getenv("DOCREADER_HOST", "0.0.0.0")
    port: int = int(os.getenv("DOCREADER_PORT", "8090"))
    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "localhost:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    minio_bucket: str = os.getenv("MINIO_BUCKET", "agentic-rag")
    minio_secure: bool = os.getenv("MINIO_SECURE", "false").lower() == "true"


settings = Settings()
