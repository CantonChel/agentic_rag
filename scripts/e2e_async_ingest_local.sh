#!/usr/bin/env bash
set -euo pipefail

APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8081}"
DOCREADER_BASE_URL="${DOCREADER_BASE_URL:-http://127.0.0.1:8090}"
KB_ID="${KB_ID:-kb-local}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
POLL_MAX_ROUNDS="${POLL_MAX_ROUNDS:-120}"

if ! command -v curl >/dev/null 2>&1; then
  echo "[e2e-local] 缺少 curl 命令。"
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "[e2e-local] 缺少 python3 命令。"
  exit 1
fi

echo "[e2e-local] 检查 docreader 健康状态..."
curl -fsS "${DOCREADER_BASE_URL}/healthz" >/dev/null

echo "[e2e-local] 检查 app 可访问性..."
http_code="$(curl -sS -o /dev/null -w "%{http_code}" "${APP_BASE_URL}/api/jobs/not-found")"
if [[ "${http_code}" != "200" && "${http_code}" != "404" ]]; then
  echo "[e2e-local] app 未就绪，HTTP=${http_code}"
  exit 1
fi

tmp_file="$(mktemp "${TMPDIR:-/tmp}/rag-local-XXXXXX")"
cat >"${tmp_file}" <<'EOF'
这是本机模式联调文档。
用于验证 java + redis + python docreader 的异步链路。
EOF

upload_url="${APP_BASE_URL}/api/knowledge-bases/${KB_ID}/knowledge/file"
metadata_json='{"source":"local-e2e","tags":["local","async-ingest"]}'

echo "[e2e-local] 上传文档到 ${upload_url}"
upload_resp="$(curl -fsS -X POST "${upload_url}" \
  -F "file=@${tmp_file};filename=local-e2e.txt;type=text/plain" \
  -F "metadata=${metadata_json}")"
echo "[e2e-local] 上传响应: ${upload_resp}"

job_id="$(printf "%s" "${upload_resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("jobId",""))')"
knowledge_id="$(printf "%s" "${upload_resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("knowledgeId",""))')"

if [[ -z "${job_id}" || -z "${knowledge_id}" ]]; then
  echo "[e2e-local] 上传响应缺少 jobId 或 knowledgeId。"
  rm -f "${tmp_file}"
  exit 1
fi

echo "[e2e-local] knowledge_id=${knowledge_id} job_id=${job_id}"

job_url="${APP_BASE_URL}/api/jobs/${job_id}"
terminal_status=""

for round in $(seq 1 "${POLL_MAX_ROUNDS}"); do
  resp="$(curl -fsS "${job_url}")"
  job_status="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
  retry_count="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("retryCount",""))')"
  error_code="$(printf "%s" "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("lastErrorCode",""))')"
  echo "[e2e-local] 轮询#${round} status=${job_status} retry=${retry_count} error=${error_code}"

  case "${job_status}" in
    success)
      terminal_status="success"
      break
      ;;
    failed|dead_letter)
      terminal_status="${job_status}"
      break
      ;;
    *)
      sleep "${POLL_INTERVAL_SECONDS}"
      ;;
  esac
done

rm -f "${tmp_file}"

if [[ "${terminal_status}" != "success" ]]; then
  echo "[e2e-local] 验收失败，最终状态=${terminal_status:-timeout}"
  exit 1
fi

echo "[e2e-local] 验收通过，任务状态为 success。"
