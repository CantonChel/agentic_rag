from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class JobSubmitRequest(BaseModel):
    job_id: str = Field(..., alias="jobId")
    knowledge_id: str = Field(..., alias="knowledgeId")
    file_url: str = Field(..., alias="fileUrl")
    callback_url: str = Field(..., alias="callbackUrl")
    pipeline_version: str = Field("v1", alias="pipelineVersion")
    options: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        allow_population_by_field_name = True


class JobSubmitResponse(BaseModel):
    accepted: bool
    remote_job_id: str


class CallbackError(BaseModel):
    code: str
    message: str


class ImageInfo(BaseModel):
    url: Optional[str] = None
    original_url: Optional[str] = None
    start_pos: Optional[int] = None
    end_pos: Optional[int] = None
    caption: Optional[str] = None
    ocr_text: Optional[str] = None


class ChunkPayload(BaseModel):
    chunk_id: str
    type: str = "text"
    seq: int
    start: Optional[int] = None
    end: Optional[int] = None
    content: str
    image_info: List[ImageInfo] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class CallbackPayload(BaseModel):
    event_id: str
    status: str
    message: Optional[str] = None
    error: Optional[CallbackError] = None
    chunks: List[ChunkPayload] = Field(default_factory=list)


class JobStatus(BaseModel):
    remote_job_id: str
    status: str
    message: Optional[str] = None
