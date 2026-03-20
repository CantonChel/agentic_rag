# Docker Compose Quick Start

## Services
- `agentic-rag-app`: Java business service (`http://localhost:8081`)
- `docreader`: Python document cleaner (`http://localhost:8090`)
- `redis`: queue backend (compose internal network only)

## Start
```bash
docker compose up --build -d
```

## Stop
```bash
docker compose down
```

## Logs
```bash
docker compose logs -f agentic-rag-app
docker compose logs -f docreader
```

## Health Check
```bash
curl http://localhost:8090/healthz
```

## End-to-End Smoke Test
```bash
./scripts/e2e_async_ingest.sh
```

If you want to run without Docker, see `local-dev.README.md`.

### Optional Env Overrides
```bash
APP_BASE_URL=http://localhost:8081 \
DOCREADER_BASE_URL=http://localhost:8090 \
KB_ID=kb-e2e \
POLL_MAX_ROUNDS=120 \
POLL_INTERVAL_SECONDS=2 \
COMPOSE_UP_MAX_RETRIES=3 \
KEEP_STACK_UP=1 \
./scripts/e2e_async_ingest.sh
```

## Important Notes
- `agentic-rag-app` and `docreader` share the same volume `/shared/knowledge-files`.
- `redis` is not exposed to host by default, to avoid local port conflicts.
- Java service posts jobs to `docreader` via `http://docreader:8090/jobs`.
- `docreader` callbacks target `http://agentic-rag-app:8081/internal/docreader/jobs/{jobId}/result`.
- Callback signature can be enabled with `DOCREADER_CALLBACK_SECRET`.
