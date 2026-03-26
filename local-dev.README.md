# Local Run (No Docker)

## 1) Prepare Redis
```bash
brew install redis
brew services start redis
redis-cli -h 127.0.0.1 -p 6379 ping
```

Expected:
```text
PONG
```

## 2) Start docreader_service (Python)
```bash
cd docreader_service
/usr/bin/python3 -m pip install --user -r requirements.txt
DOCREADER_PORT=8090 /usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port 8090
```

## 3) Start agentic_rag_app (Java)
```bash
cd agentic_rag_app
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
SERVER_PORT=8081 \
REDIS_HOST=127.0.0.1 \
REDIS_PORT=6379 \
INGEST_ASYNC_ENABLED=true \
INGEST_FILE_ROOT=/tmp/knowledge-files-local \
DOCREADER_BASE_URL=http://127.0.0.1:8090 \
DOCREADER_READ_PATH=/read \
./mvnw -q spring-boot:run
```

## 3.1) Postgres indexes (pgvector + BM25)
If you enable `SPRING_PROFILES_ACTIVE=postgres`, run:
```sql
-- scripts/postgres_init.sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE INDEX IF NOT EXISTS idx_chunk_fts
ON chunk
USING GIN (to_tsvector('simple', content));
CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2
ON embedding
USING ivfflat (vector_json::vector);
ANALYZE chunk;
ANALYZE embedding;
```

## 4) Run E2E smoke test
In a new terminal from project root:
```bash
./scripts/e2e_async_ingest_local.sh
```

Optional env overrides:
```bash
APP_BASE_URL=http://127.0.0.1:8081 \
DOCREADER_BASE_URL=http://127.0.0.1:8090 \
KB_ID=kb-local \
POLL_MAX_ROUNDS=120 \
POLL_INTERVAL_SECONDS=2 \
./scripts/e2e_async_ingest_local.sh
```

## SSE 事件契约（回合 + 工具状态）
流式接口通过 SSE 输出事件，`data` 为 JSON。推荐前端按 `turnId + sequenceId` 渲染。

### 事件类型
- `turn_start`：一次用户请求回合开始
- `thinking`：思考内容（支持多轮，`roundId` 区分）
- `tool_start`：工具开始执行
- `tool_end`：工具执行结束（`success/error/timeout`）
- `delta`：回答正文增量
- `done`：本次模型流结束
- `turn_end`：回合结束（收尾事件）
- `error`：错误消息

### 通用字段（推荐前端统一读取）
```json
{
  "type": "tool_start",
  "turnId": "7f0e7d70-9ed0-4d0f-86e5-3f5b8775ef2e",
  "sequenceId": 5,
  "ts": 1760000000000,
  "roundId": 1
}
```

### 工具生命周期字段
`tool_start`:
```json
{
  "type": "tool_start",
  "toolCallId": "call_1",
  "toolName": "search_knowledge_base",
  "argsPreview": {"query":"架构"}
}
```

`tool_end`:
```json
{
  "type": "tool_end",
  "toolCallId": "call_1",
  "toolName": "search_knowledge_base",
  "status": "success",
  "durationMs": 183,
  "resultPreview": {"text":"..."},
  "error": null
}
```

### 顺序与配对约束
1. 同一回合内 `sequenceId` 单调递增。
2. 每个 `tool_start` 必有一个同 `toolCallId` 的 `tool_end`。
3. 典型时序：`turn_start -> thinking/tool_start/tool_end/delta... -> done -> turn_end`。
