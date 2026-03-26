# docreader_service

独立 Python 文档清洗服务（与 `agentic_rag_app` 同级目录）。

## 功能
- `POST /read`：同步读取单篇文档，直接返回整篇 `ReadResponse`。
- `GET /healthz`：健康检查。
- 支持解析：`pdf`、`docx`、`txt`、`md`、`csv`、`json`、`html/htm`。
- `docx`/`pdf` 会尽量产出整篇 Markdown，并保留图片引用位置。
- `docreader` 不负责分块、不负责 embedding、不负责图片落存。

## 请求输入（`POST /read`）
```json
{
  "jobId": "业务端job_id",
  "knowledgeId": "knowledge_id",
  "fileUrl": "文件URL或本地路径",
  "pipelineVersion": "v1",
  "options": {
    "user_id": "anonymous"
  }
}
```

## `ReadResponse` 输出（成功）
```json
{
  "markdownContent": "# Title\n\n正文...\n\n![](images/abc.png)",
  "imageRefs": [
    {
      "originalRef": "images/abc.png",
      "fileName": "abc.png",
      "mimeType": "image/png",
      "bytesBase64": "iVBORw0KGgoAAA..."
    }
  ],
  "metadata": {
    "source_ext": ".pdf",
    "user_id": "anonymous",
    "job_id": "业务端job_id"
  },
  "error": ""
}
```

## `ReadResponse` 输出（失败）
```json
{
  "markdownContent": "",
  "imageRefs": [],
  "metadata": {
    "job_id": "业务端job_id"
  },
  "error": "unsupported_file: unsupported extension: .xlsx"
}
```

说明：失败信息固定放在 `error` 字符串中，格式为 `error_code: message`，供业务端分类处理。

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
