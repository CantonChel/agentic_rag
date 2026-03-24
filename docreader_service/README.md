# docreader_service

独立 Python 文档清洗服务（与 `agentic_rag_app` 同级目录）。

## 功能
- `POST /jobs`：接收清洗任务，异步执行。
- `GET /jobs/{remote_job_id}`：查询任务状态。
- `GET /healthz`：健康检查。
- 清洗完成后回调业务端（支持 `HMAC-SHA256` 签名）。
- 支持解析：`pdf`、`docx`、`txt`、`md`、`csv`、`json`、`html/htm`。
- `docx` 会通过 `markitdown` 统一转换为 Markdown 后再分块。

## 任务输入（`POST /jobs`）
```json
{
  "jobId": "业务端job_id",
  "knowledgeId": "knowledge_id",
  "fileUrl": "文件URL或本地路径",
  "callbackUrl": "业务端回调地址",
  "pipelineVersion": "v1",
  "options": {
    "chunk_size": 1000,
    "chunk_overlap": 150
  }
}
```

## 回调输出（成功）
```json
{
  "event_id": "uuid",
  "status": "success",
  "message": "ok",
  "chunks": [
    {
      "chunk_id": "job_id:0",
      "type": "text",
      "seq": 0,
      "start": 0,
      "end": 1000,
      "content": "...",
      "image_info": [],
      "metadata": {"source_ext": ".pdf"}
    }
  ]
}
```

## 回调输出（失败）
```json
{
  "event_id": "uuid",
  "status": "failed",
  "message": "...",
  "error": {
    "code": "unsupported_file | corrupted_file | service_unavailable | schema_invalid",
    "message": "..."
  },
  "chunks": []
}
```

## 本地运行
```bash
cd docreader_service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8090
```

## 关键环境变量
- `DOCREADER_PORT`（默认 `8090`）
- `DOCREADER_WORKER_COUNT`（默认 `2`）
- `DOCREADER_CALLBACK_SECRET`（为空则不签名）
- `DOCREADER_SIGNATURE_HEADER`（默认 `X-Docreader-Signature`）
- `DOCREADER_TIMESTAMP_HEADER`（默认 `X-Docreader-Timestamp`）
