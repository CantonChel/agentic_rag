# Docker Compose Quick Start

## Services
- `agentic-rag-app`: Java business service (`http://localhost:8081`)
- `docreader`: Python document cleaner (`http://localhost:8090`)
- `redis`: queue backend (`localhost:6379`)

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

## Important Notes
- `agentic-rag-app` and `docreader` share the same volume `/shared/knowledge-files`.
- Java service posts jobs to `docreader` via `http://docreader:8090/jobs`.
- `docreader` callbacks target `http://agentic-rag-app:8081/internal/docreader/jobs/{jobId}/result`.
- Callback signature can be enabled with `DOCREADER_CALLBACK_SECRET`.
