#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/.logs"
mkdir -p "${LOG_DIR}"

JAVA_PORT="${JAVA_PORT:-8081}"
DOCREADER_PORT="${DOCREADER_PORT:-8090}"
REDIS_PORT="${REDIS_PORT:-6379}"

echo "==> Stopping services on ports ${JAVA_PORT}, ${DOCREADER_PORT}, ${REDIS_PORT}"

kill_by_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti tcp:"${port}" || true)"
  if [[ -n "${pids}" ]]; then
    echo "Stopping process(es) on port ${port}: ${pids}"
    kill ${pids} || true
    sleep 1
    pids="$(lsof -ti tcp:"${port}" || true)"
    if [[ -n "${pids}" ]]; then
      echo "Force killing process(es) on port ${port}: ${pids}"
      kill -9 ${pids} || true
    fi
  else
    echo "No process found on port ${port}"
  fi
}

if command -v redis-cli >/dev/null 2>&1; then
  echo "Trying redis-cli shutdown on port ${REDIS_PORT}"
  redis-cli -h 127.0.0.1 -p "${REDIS_PORT}" shutdown || true
fi

kill_by_port "${JAVA_PORT}"
kill_by_port "${DOCREADER_PORT}"
kill_by_port "${REDIS_PORT}"

echo "==> Starting services"

if command -v redis-server >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    echo "Starting redis via brew services"
    brew services start redis || true
  else
    echo "Starting redis-server directly"
    nohup redis-server --port "${REDIS_PORT}" > "${LOG_DIR}/redis.log" 2>&1 &
  fi
else
  echo "redis-server not found; skipping redis start"
fi

echo "Starting docreader_service (port ${DOCREADER_PORT})"
(
  cd "${ROOT_DIR}/docreader_service"
  nohup /usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port "${DOCREADER_PORT}" > "${LOG_DIR}/docreader.log" 2>&1 &
)

echo "Starting agentic_rag_app (port ${JAVA_PORT})"
(
  cd "${ROOT_DIR}/agentic_rag_app"
  export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}"
  export SERVER_PORT="${JAVA_PORT}"
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export REDIS_PORT="${REDIS_PORT}"
  export INGEST_ASYNC_ENABLED="${INGEST_ASYNC_ENABLED:-true}"
  export INGEST_FILE_ROOT="${INGEST_FILE_ROOT:-/tmp/knowledge-files-local}"
  export DOCREADER_BASE_URL="${DOCREADER_BASE_URL:-http://127.0.0.1:${DOCREADER_PORT}}"
  export DOCREADER_CALLBACK_BASE_URL="${DOCREADER_CALLBACK_BASE_URL:-http://127.0.0.1:${JAVA_PORT}}"
  export DOCREADER_CALLBACK_SECRET="${DOCREADER_CALLBACK_SECRET:-}"
  export OPENAI_API_KEY="${OPENAI_API_KEY:-}"
  export MINIMAX_API_KEY="${MINIMAX_API_KEY:-}"
  export SILICONFLOW_API_KEY="${SILICONFLOW_API_KEY:-}"
  nohup ./mvnw -q spring-boot:run > "${LOG_DIR}/app.log" 2>&1 &
)

echo "==> Done. Logs: ${LOG_DIR}/redis.log, ${LOG_DIR}/docreader.log, ${LOG_DIR}/app.log"
