from fastapi import FastAPI

from config import settings
from models import ReadRequest, ReadResponse
from parser import read_document_safe

app = FastAPI(title="docreader-service", version="0.1.0")


@app.get("/healthz")
async def healthz() -> dict:
    return {"ok": True}


@app.post("/read", response_model=ReadResponse)
async def read(req: ReadRequest) -> ReadResponse:
    return await read_document_safe(req.job_id, req.file_url, req.options)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
