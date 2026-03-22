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
DOCREADER_CALLBACK_BASE_URL=http://127.0.0.1:8081 \
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

## SSE 事件契约（思考能力）
流式接口会通过 SSE 输出事件，`data` 为 JSON。

### 事件类型
- `delta`：普通内容增量
- `done`：本轮结束
- `error`：错误消息
- `thinking`：思考内容（默认应展示，可支持“隐藏思考”开关）

### thinking 事件字段
```json
{
  "type": "thinking",
  "content": "思考内容",
  "source": "thinking_tool | reasoning_field | assistant_content",
  "originModel": "gpt-xxx",
  "roundId": 1
}
```

### 思考来源与优先级
1. `thinking_tool`：来自 `thinking` 工具输出（最高优先级）
2. `reasoning_field`：来自模型原生 `reasoning_content` / `thinking` / `reasoning` 字段
3. `assistant_content`：assistant 内容中的显式分步文本（如“步骤 1/2/3”）

只要任一来源存在，都会发 `thinking` 事件；如有更高优先级来源，忽略低优先级来源。
`<think>` 标签不作为可靠来源，默认不解析。
