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
