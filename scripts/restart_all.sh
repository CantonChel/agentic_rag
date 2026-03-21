#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/.logs"
mkdir -p "${LOG_DIR}"

JAVA_PORT="${JAVA_PORT:-8081}"
DOCREADER_PORT="${DOCREADER_PORT:-8090}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_DOCKER_NAME="${REDIS_DOCKER_NAME:-rag-redis}"
REDIS_MODE_RAW="${REDIS_MODE:-docker}"
PG_PORT="${PG_PORT:-5432}"
PG_DOCKER_NAME="${PG_DOCKER_NAME:-rag-postgres}"
PG_DOCKER_IMAGE="${PG_DOCKER_IMAGE:-pgvector/pgvector:pg16}"
PG_MODE_RAW="${PG_MODE:-docker}"
PG_DB="${PG_DB:-agentic_rag}"
PG_USER="${PG_USER:-agentic}"
PG_PASSWORD="${PG_PASSWORD:-agentic}"
DOTENV_PATH="${ROOT_DIR}/.env"
REDIS_MODE="$(printf '%s' "${REDIS_MODE_RAW}" | tr '[:upper:]' '[:lower:]')"
PG_MODE="$(printf '%s' "${PG_MODE_RAW}" | tr '[:upper:]' '[:lower:]')"

docker_ready() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1
}

java_major() {
  local java_bin="$1"
  local version_line
  version_line="$("${java_bin}" -version 2>&1 | head -n 1)"
  if [[ "${version_line}" =~ \"1\.([0-9]+)\. ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "${version_line}" =~ \"([0-9]+)\. ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

if [[ "${REDIS_MODE}" != "docker" && "${REDIS_MODE}" != "local" ]]; then
  echo "Invalid REDIS_MODE=${REDIS_MODE_RAW}. Use docker or local."
  exit 1
fi
if [[ "${PG_MODE}" != "docker" && "${PG_MODE}" != "local" ]]; then
  echo "Invalid PG_MODE=${PG_MODE_RAW}. Use docker or local."
  exit 1
fi

echo "==> Redis mode: ${REDIS_MODE}"
echo "==> Postgres mode: ${PG_MODE}"
echo "==> Stopping services on ports ${JAVA_PORT}, ${DOCREADER_PORT}, ${REDIS_PORT}, ${PG_PORT}"

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

if [[ "${REDIS_MODE}" == "docker" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if docker ps -a --format '{{.Names}}' | grep -q "^${REDIS_DOCKER_NAME}\$"; then
      echo "Stopping docker redis container: ${REDIS_DOCKER_NAME}"
      docker stop "${REDIS_DOCKER_NAME}" >/dev/null 2>&1 || true
      docker rm "${REDIS_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
else
  if command -v redis-cli >/dev/null 2>&1; then
    echo "Trying redis-cli shutdown on port ${REDIS_PORT}"
    redis-cli -h 127.0.0.1 -p "${REDIS_PORT}" shutdown || true
  fi
fi

if [[ "${PG_MODE}" == "docker" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if docker ps -a --format '{{.Names}}' | grep -q "^${PG_DOCKER_NAME}\$"; then
      echo "Stopping docker postgres container: ${PG_DOCKER_NAME}"
      docker stop "${PG_DOCKER_NAME}" >/dev/null 2>&1 || true
      docker rm "${PG_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
fi

kill_by_port "${JAVA_PORT}"
kill_by_port "${DOCREADER_PORT}"
kill_by_port "${REDIS_PORT}"
if [[ "${PG_MODE}" == "docker" ]]; then
  kill_by_port "${PG_PORT}"
fi

echo "==> Starting services"

if docker_ready; then
  if [[ "${REDIS_MODE}" == "docker" ]]; then
    echo "Pulling redis image: redis:7"
    docker pull redis:7 >/dev/null
    echo "Starting redis via docker: ${REDIS_DOCKER_NAME}"
    docker run -d --name "${REDIS_DOCKER_NAME}" -p "${REDIS_PORT}:6379" redis:7 >/dev/null
  elif command -v redis-server >/dev/null 2>&1; then
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
else
  if [[ "${REDIS_MODE}" == "docker" ]]; then
    echo "Docker is required for REDIS_MODE=docker."
    echo "Set REDIS_MODE=local if you want to use local redis service."
    exit 1
  elif command -v redis-server >/dev/null 2>&1; then
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
fi

if [[ "${PG_MODE}" == "docker" ]]; then
  if ! docker_ready; then
    echo "Docker is required for PG_MODE=docker."
    echo "Set PG_MODE=local and provide DB_URL/DB_USER/DB_PASSWORD for local postgres."
    exit 1
  fi
  echo "Pulling postgres image: ${PG_DOCKER_IMAGE}"
  docker pull "${PG_DOCKER_IMAGE}" >/dev/null
  echo "Starting postgres via docker: ${PG_DOCKER_NAME}"
  docker run -d --name "${PG_DOCKER_NAME}" \
    -e POSTGRES_DB="${PG_DB}" \
    -e POSTGRES_USER="${PG_USER}" \
    -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
    -p "${PG_PORT}:5432" \
    "${PG_DOCKER_IMAGE}" >/dev/null
elif [[ -z "${DB_URL:-}" || -z "${DB_USER:-}" || -z "${DB_PASSWORD:-}" ]]; then
  echo "PG_MODE=local requires DB_URL, DB_USER and DB_PASSWORD."
  echo "Example:"
  echo "  PG_MODE=local DB_URL=jdbc:postgresql://127.0.0.1:5432/agentic_rag DB_USER=agentic DB_PASSWORD=agentic ./scripts/restart_all.sh"
  exit 1
fi

echo "Starting docreader_service (port ${DOCREADER_PORT})"
(
  cd "${ROOT_DIR}/docreader_service"
  nohup /usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port "${DOCREADER_PORT}" > "${LOG_DIR}/docreader.log" 2>&1 &
)

echo "Starting agentic_rag_app (port ${JAVA_PORT})"
(
  cd "${ROOT_DIR}/agentic_rag_app"
  if [[ -f "${DOTENV_PATH}" ]]; then
    set -a
    # shellcheck disable=SC1090
    . "${DOTENV_PATH}"
    set +a
  fi
  export DOTENV_DIR="${ROOT_DIR}"
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    current_java_major="$(java_major "${JAVA_HOME}/bin/java" || echo 0)"
  else
    current_java_major=0
  fi
  if [[ "${current_java_major}" -lt 17 ]]; then
    if [[ -x "/usr/libexec/java_home" ]]; then
      detected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
      if [[ -n "${detected_java_home}" ]]; then
        JAVA_HOME="${detected_java_home}"
      fi
    fi
  fi
  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
  fi
  if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "JAVA_HOME invalid: ${JAVA_HOME}"
    echo "Please export JAVA_HOME to a valid JDK path before running this script."
    exit 1
  fi
  current_java_major="$(java_major "${JAVA_HOME}/bin/java" || echo 0)"
  if [[ "${current_java_major}" -lt 17 ]]; then
    echo "JAVA_HOME points to Java ${current_java_major}, but Java 17+ is required."
    echo "Current JAVA_HOME: ${JAVA_HOME}"
    exit 1
  fi
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
  export SERVER_PORT="${JAVA_PORT}"
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export REDIS_PORT="${REDIS_PORT}"
  if [[ "${PG_MODE}" == "docker" ]]; then
    export DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}}"
    export DB_USER="${DB_USER:-${PG_USER}}"
    export DB_PASSWORD="${DB_PASSWORD:-${PG_PASSWORD}}"
  fi
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
