from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class ReadRequest(BaseModel):
    job_id: str = Field(..., alias="jobId")
    knowledge_id: str = Field(..., alias="knowledgeId")
    file_url: str = Field(..., alias="fileUrl")
    pipeline_version: str = Field("v1", alias="pipelineVersion")
    options: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        allow_population_by_field_name = True


class ImageRef(BaseModel):
    original_ref: str = Field(..., alias="originalRef")
    file_name: str = Field(..., alias="fileName")
    mime_type: str = Field(..., alias="mimeType")
    bytes_base64: Optional[str] = Field(default=None, alias="bytesBase64")

    class Config:
        allow_population_by_field_name = True


class ReadResponse(BaseModel):
    markdown_content: str = Field("", alias="markdownContent")
    image_refs: List[ImageRef] = Field(default_factory=list, alias="imageRefs")
    metadata: Dict[str, str] = Field(default_factory=dict)
    error: str = ""

    class Config:
        allow_population_by_field_name = True
