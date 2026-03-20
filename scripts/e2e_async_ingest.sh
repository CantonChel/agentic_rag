#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

APP_BASE_URL="${APP_BASE_URL:-http://localhost:8081}"
DOCREADER_BASE_URL="${DOCREADER_BASE_URL:-http://localhost:8090}"
KB_ID="${KB_ID:-kb-e2e}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
POLL_MAX_ROUNDS="${POLL_MAX_ROUNDS:-120}"
KEEP_STACK_UP="${KEEP_STACK_UP:-1}"
COMPOSE_UP_MAX_RETRIES="${COMPOSE_UP_MAX_RETRIES:-3}"
COMPOSE_UP_SLEEP_SECONDS="${COMPOSE_UP_SLEEP_SECONDS:-5}"

if ! command -v docker >/dev/null 2>&1; then
  echo "[e2e] 缺少 docker 命令，无法执行验收。"
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "[e2e] 缺少 curl 命令，无法执行验收。"
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "[e2e] 缺少 python3 命令，无法解析 JSON。"
  exit 1
fi

cleanup() {
  if [[ "${KEEP_STACK_UP}" == "0" ]]; then
    echo "[e2e] 停止 docker compose 环境..."
    docker compose down
  fi
}
trap cleanup EXIT

echo "[e2e] 启动 compose 服务..."
compose_started=0
for attempt in $(seq 1 "${COMPOSE_UP_MAX_RETRIES}"); do
  if docker compose up --build -d; then
    compose_started=1
    break
  fi
  if [[ "${attempt}" -lt "${COMPOSE_UP_MAX_RETRIES}" ]]; then
    echo "[e2e] compose 启动失败，${COMPOSE_UP_SLEEP_SECONDS}s 后重试 (${attempt}/${COMPOSE_UP_MAX_RETRIES})..."
    sleep "${COMPOSE_UP_SLEEP_SECONDS}"
  fi
done

if [[ "${compose_started}" != "1" ]]; then
  echo "[e2e] compose 启动失败，达到最大重试次数 ${COMPOSE_UP_MAX_RETRIES}。"
  exit 1
fi

echo "[e2e] 等待 docreader 健康检查..."
for _ in $(seq 1 60); do
  if curl -fsS "${DOCREADER_BASE_URL}/healthz" >/dev/null; then
    break
  fi
  sleep 1
done
curl -fsS "${DOCREADER_BASE_URL}/healthz" >/dev/null

echo "[e2e] 等待业务服务可访问..."
for _ in $(seq 1 60); do
  if curl -sS -o /dev/null -w "%{http_code}" "${APP_BASE_URL}/api/jobs/not-found" | grep -Eq '^(200|404)$'; then
    break
  fi
  sleep 1
done

tmp_file="$(mktemp "${TMPDIR:-/tmp}/rag-e2e-XXXXXX")"
cat >"${tmp_file}" <<'EOF'
这是一个异步清洗验收样例文档。
我们需要验证上传后立即返回，随后任务状态能变为 success。
EOF

metadata_json='{"source":"e2e-script","tags":["e2e","async-ingest"]}'
upload_url="${APP_BASE_URL}/api/knowledge-bases/${KB_ID}/knowledge/file"

echo "[e2e] 上传文档到 ${upload_url}"
upload_resp="$(curl -fsS -X POST "${upload_url}" \
  -F "file=@${tmp_file};filename=e2e.txt;type=text/plain" \
  -F "metadata=${metadata_json}")"

echo "[e2e] 上传响应: ${upload_resp}"

job_id="$(printf "%s" "${upload_resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("jobId",""))')"
knowledge_id="$(printf "%s" "${upload_resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("knowledgeId",""))')"
initial_status="$(printf "%s" "${upload_resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"

if [[ -z "${job_id}" || -z "${knowledge_id}" ]]; then
  echo "[e2e] 上传响应缺少 jobId 或 knowledgeId。"
  exit 1
fi

echo "[e2e] knowledge_id=${knowledge_id} job_id=${job_id} initial_status=${initial_status}"

job_url="${APP_BASE_URL}/api/jobs/${job_id}"
terminal_status=""

for round in $(seq 1 "${POLL_MAX_ROUNDS}"); do
  resp="$(curl -fsS "${job_url}")"
  status="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
  retry_count="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("retryCount",""))')"
  error_code="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("lastErrorCode",""))')"
  echo "[e2e] 轮询#${round} status=${status} retry=${retry_count} error=${error_code}"

  case "${status}" in
    success)
      terminal_status="success"
      break
      ;;
    failed|dead_letter)
      terminal_status="${status}"
      break
      ;;
    *)
      sleep "${POLL_INTERVAL_SECONDS}"
      ;;
  esac
done

if [[ "${terminal_status}" != "success" ]]; then
  echo "[e2e] 验收失败，最终状态=${terminal_status:-timeout}"
  echo "[e2e] 最近业务日志:"
  docker compose logs --tail=80 agentic-rag-app || true
  echo "[e2e] 最近 docreader 日志:"
  docker compose logs --tail=80 docreader || true
  exit 1
fi

echo "[e2e] 验收通过，任务状态为 success。"
rm -f "${tmp_file}"
