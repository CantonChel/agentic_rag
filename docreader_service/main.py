from fastapi import FastAPI, HTTPException

from config import settings
from models import JobStatus, JobSubmitRequest, JobSubmitResponse
from worker import job_manager

app = FastAPI(title="docreader-service", version="0.1.0")


@app.on_event("startup")
async def on_startup() -> None:
    await job_manager.start()


@app.on_event("shutdown")
async def on_shutdown() -> None:
    await job_manager.stop()


@app.get("/healthz")
async def healthz() -> dict:
    return {"ok": True}


@app.post("/jobs", response_model=JobSubmitResponse)
async def submit_job(req: JobSubmitRequest) -> JobSubmitResponse:
    if not req.callback_url:
        raise HTTPException(status_code=400, detail="callbackUrl is required")
    remote_job_id = await job_manager.submit(req)
    return JobSubmitResponse(accepted=True, remote_job_id=remote_job_id)


@app.get("/jobs/{remote_job_id}", response_model=JobStatus)
async def get_job_status(remote_job_id: str) -> JobStatus:
    return job_manager.get_status(remote_job_id)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
