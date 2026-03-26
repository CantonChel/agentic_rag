#!/usr/bin/env bash
# ============================================================================
# restart_all.sh - 本地开发环境一键启动脚本
#
# 功能说明：
#   1. 停止所有旧的服务进程（包括 Docker 容器和本地进程）
#   2. 启动基础设施服务：Redis、PostgreSQL、MinIO（通过 Docker）
#   3. 启动业务服务：docreader（本地 Python 进程）、agentic-rag-app（本地 Java 进程）
#
# 使用方式：
#   ./scripts/restart_all.sh                    # 使用默认配置启动
#   REDIS_MODE=local ./scripts/restart_all.sh   # 使用本地 Redis
#   PG_MODE=local DB_URL=xxx ./scripts/restart_all.sh  # 使用本地 PostgreSQL
#
# 日志位置：
#   .logs/app.log       - Java 应用日志
#   .logs/docreader.log - 文档解析服务日志
#   .logs/redis.log     - Redis 日志（仅本地模式）
# ============================================================================

# set -e: 任何命令返回非零退出码时立即终止脚本
# set -u: 使用未定义变量时报错（防止拼写错误）
# set -o pipefail: 管道中任何一个命令失败，整个管道返回失败状态
set -euo pipefail

RESTART_DEBUG="${RESTART_DEBUG:-false}"
STARTUP_TIMEOUT_SEC="${STARTUP_TIMEOUT_SEC:-90}"
STARTUP_POLL_INTERVAL_SEC="${STARTUP_POLL_INTERVAL_SEC:-1}"
if [[ "${RESTART_DEBUG}" == "true" ]]; then
  set -x
fi

# ============================================================================
# 路径和目录配置
# ============================================================================

# 获取脚本所在目录的父目录（项目根目录）
# ${BASH_SOURCE[0]} 是当前脚本的绝对路径
# dirname 获取目录部分
# cd ... && pwd 获取规范化的绝对路径
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 日志目录，用于存放各服务的日志文件
LOG_DIR="${ROOT_DIR}/.logs"
mkdir -p "${LOG_DIR}"

# ============================================================================
# 端口配置
# 使用 ${VAR:-default} 语法：如果 VAR 已设置则使用其值，否则使用 default
# ============================================================================

JAVA_PORT="${JAVA_PORT:-8081}"           # Java 业务服务端口
DOCREADER_PORT="${DOCREADER_PORT:-8090}" # Python 文档解析服务端口
REDIS_PORT="${REDIS_PORT:-6379}"         # Redis 端口
PG_PORT="${PG_PORT:-5432}"               # PostgreSQL 端口
MINIO_PORT="${MINIO_PORT:-9000}"         # MinIO API 端口
MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-9001}"  # MinIO Web 控制台端口

# ============================================================================
# Redis 配置
# ============================================================================

REDIS_DOCKER_NAME="${REDIS_DOCKER_NAME:-rag-redis}"  # Docker 容器名称
REDIS_MODE_RAW="${REDIS_MODE:-docker}"               # 运行模式: docker 或 local

# ============================================================================
# PostgreSQL 配置
# ============================================================================

PG_DOCKER_NAME="${PG_DOCKER_NAME:-rag-postgres}"                    # Docker 容器名称
PG_DOCKER_IMAGE="${PG_DOCKER_IMAGE:-pgvector/pgvector:pg16}"        # Docker 镜像（带 pgvector 扩展）
PG_DOCKER_VOLUME="${PG_DOCKER_VOLUME:-rag-postgres-data}"           # Docker 数据卷名称
PG_MODE_RAW="${PG_MODE:-docker}"                                    # 运行模式: docker 或 local
PG_DB="${PG_DB:-agentic_rag}"                                       # 数据库名称
PG_USER="${PG_USER:-agentic}"                                       # 数据库用户名
PG_PASSWORD="${PG_PASSWORD:-agentic}"                               # 数据库密码

# ============================================================================
# MinIO 配置（对象存储）
# ============================================================================

MINIO_DOCKER_NAME="${MINIO_DOCKER_NAME:-agentic-rag-minio}"
MINIO_DOCKER_IMAGE="${MINIO_DOCKER_IMAGE:-minio/minio:RELEASE.2025-02-28T09-55-16Z}"
MINIO_DOCKER_VOLUME="${MINIO_DOCKER_VOLUME:-agentic-rag-minio-data}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"      # 访问密钥（相当于用户名）
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"      # 秘密密钥（相当于密码）
MINIO_BUCKET="${MINIO_BUCKET:-agentic-rag}"             # 存储桶名称
MINIO_SECURE="${MINIO_SECURE:-false}"                   # 是否使用 HTTPS

# MinIO 端点地址配置
# docreader 需要直接访问 MinIO，所以使用 127.0.0.1:port 格式
# Java 应用使用 HTTP URL 格式
MINIO_ENDPOINT_DOCREADER="${MINIO_ENDPOINT_DOCREADER:-127.0.0.1:${MINIO_PORT}}"
MINIO_ENDPOINT_APP="${MINIO_ENDPOINT_APP:-http://127.0.0.1:${MINIO_PORT}}"

# ============================================================================
# 存储配置
# ============================================================================

# 文件存储后端: minio（对象存储）或 local（本地文件系统）
INGEST_FILE_STORAGE_BACKEND="${INGEST_FILE_STORAGE_BACKEND:-minio}"

# 环境变量文件路径
DOTENV_PATH="${ROOT_DIR}/.env"
APP_LOG_FILE="${LOG_DIR}/app.log"
APP_BUILD_LOG_FILE="${LOG_DIR}/app-build.log"
DOCREADER_LOG_FILE="${LOG_DIR}/docreader.log"
REDIS_LOG_FILE="${LOG_DIR}/redis.log"
APP_PID_FILE="${LOG_DIR}/app-mvn.pid"
DOCREADER_PID_FILE="${LOG_DIR}/docreader.pid"

# ============================================================================
# 模式标准化处理
# 将模式值转换为小写，避免大小写不一致的问题
# ============================================================================

REDIS_MODE="$(printf '%s' "${REDIS_MODE_RAW}" | tr '[:upper:]' '[:lower:]')"
PG_MODE="$(printf '%s' "${PG_MODE_RAW}" | tr '[:upper:]' '[:lower:]')"
DOCREADER_PYTHON_BIN="${DOCREADER_PYTHON_BIN:-}"
if [[ -z "${DOCREADER_PYTHON_BIN}" ]]; then
  if [[ -x "${ROOT_DIR}/.venv/bin/python" ]]; then
    DOCREADER_PYTHON_BIN="${ROOT_DIR}/.venv/bin/python"
  else
    DOCREADER_PYTHON_BIN="/usr/bin/python3"
  fi
fi

# ============================================================================
# 工具函数定义
# ============================================================================

# 检查 Docker 是否可用（已安装且正在运行）
docker_ready() {
  # command -v 检查命令是否存在
  # >/dev/null 2>&1 丢弃所有输出
  command -v docker >/dev/null 2>&1 || return 1
  # docker info 需要守护进程运行才能成功
  docker info >/dev/null 2>&1
}

# 检查指定名称的 Docker 容器是否存在（包括已停止的）
docker_container_exists() {
  local name="$1"
  # docker ps -a 列出所有容器（包括已停止的）
  # --format '{{.Names}}' 只输出容器名称
  # grep -q 静默匹配，不输出结果，只返回退出码
  docker ps -a --format '{{.Names}}' | grep -q "^${name}\$"
}

# 检查指定名称的 Docker 容器是否正在运行
docker_container_running() {
  local name="$1"
  # docker ps（不带 -a）只列出正在运行的容器
  docker ps --format '{{.Names}}' | grep -q "^${name}\$"
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

tail_log_file() {
  local file="$1"
  local lines="${2:-80}"
  if [[ -f "${file}" ]]; then
    log "---- tail ${file} (last ${lines} lines) ----"
    tail -n "${lines}" "${file}" || true
  else
    log "log file not found: ${file}"
  fi
}

wait_for_port() {
  local service_name="$1"
  local port="$2"
  local timeout="${3:-60}"
  local elapsed=0
  while (( elapsed < timeout )); do
    if lsof -iTCP:"${port}" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
      log "${service_name} is listening on port ${port}"
      return 0
    fi
    sleep "${STARTUP_POLL_INTERVAL_SEC}"
    elapsed=$((elapsed + STARTUP_POLL_INTERVAL_SEC))
  done
  log "timeout waiting for ${service_name} on port ${port} (${timeout}s)"
  return 1
}

wait_for_http_ok() {
  local service_name="$1"
  local url="$2"
  local timeout="${3:-30}"
  local elapsed=0
  while (( elapsed < timeout )); do
    if curl -fsS -m 2 "${url}" >/dev/null 2>&1; then
      log "${service_name} HTTP check passed: ${url}"
      return 0
    fi
    sleep "${STARTUP_POLL_INTERVAL_SEC}"
    elapsed=$((elapsed + STARTUP_POLL_INTERVAL_SEC))
  done
  log "timeout waiting HTTP check for ${service_name}: ${url} (${timeout}s)"
  return 1
}

# 获取 Java 主版本号
# 例如: 17, 21, 8
java_major() {
  local java_bin="$1"
  local version_line
  # java -version 输出到 stderr，所以用 2>&1 重定向
  version_line="$("${java_bin}" -version 2>&1 | head -n 1)"
  
  # 匹配旧版本格式: "1.8.0_xxx"
  if [[ "${version_line}" =~ \"1\.([0-9]+)\. ]]; then
    echo "${BASH_REMATCH[1]}"  # BASH_REMATCH 存储正则匹配的捕获组
    return 0
  fi
  # 匹配新版本格式: "17.0.1" 或 "21.0.2"
  if [[ "${version_line}" =~ \"([0-9]+)\. ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

# 确保 docreader 的 Python 依赖已安装
ensure_docreader_dependency() {
  local missing=()
  local in_venv=0
  if "${DOCREADER_PYTHON_BIN}" -c 'import sys; raise SystemExit(0 if sys.prefix != getattr(sys, "base_prefix", sys.prefix) else 1)'; then
    in_venv=1
  fi
  if ! "${DOCREADER_PYTHON_BIN}" -c "import minio" >/dev/null 2>&1; then
    missing+=("minio==7.2.12")
  fi
  if ! "${DOCREADER_PYTHON_BIN}" -c "import markitdown" >/dev/null 2>&1; then
    if "${DOCREADER_PYTHON_BIN}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'; then
      missing+=("markitdown[docx,pdf,xls,xlsx]>=0.1.3")
    else
      echo "Warning: ${DOCREADER_PYTHON_BIN} < Python 3.10, cannot auto-install markitdown. docx->md parsing requires Python 3.10+."
    fi
  fi
  if [[ ${#missing[@]} -eq 0 ]]; then
    return 0
  fi
  echo "Installing python dependency for docreader: ${missing[*]}"
  if ! "${DOCREADER_PYTHON_BIN}" -m pip --version >/dev/null 2>&1; then
    if [[ "${in_venv}" -eq 1 ]]; then
      "${DOCREADER_PYTHON_BIN}" -m ensurepip >/dev/null 2>&1 || true
    else
      "${DOCREADER_PYTHON_BIN}" -m ensurepip --user >/dev/null 2>&1 || true
    fi
  fi
  if [[ "${in_venv}" -eq 1 ]]; then
    "${DOCREADER_PYTHON_BIN}" -m pip install "${missing[@]}" >/dev/null
  else
    "${DOCREADER_PYTHON_BIN}" -m pip install --user "${missing[@]}" >/dev/null
  fi
}

# ============================================================================
# 参数校验
# ============================================================================

if [[ "${REDIS_MODE}" != "docker" && "${REDIS_MODE}" != "local" ]]; then
  echo "Invalid REDIS_MODE=${REDIS_MODE_RAW}. Use docker or local."
  exit 1
fi
if [[ "${PG_MODE}" != "docker" && "${PG_MODE}" != "local" ]]; then
  echo "Invalid PG_MODE=${PG_MODE_RAW}. Use docker or local."
  exit 1
fi

# 输出当前配置信息
log "Redis mode: ${REDIS_MODE}"
log "Postgres mode: ${PG_MODE}"
log "Storage backend: ${INGEST_FILE_STORAGE_BACKEND}"
log "Startup timeout: ${STARTUP_TIMEOUT_SEC}s"
log "Stopping services on ports ${JAVA_PORT}, ${DOCREADER_PORT}, ${REDIS_PORT}, ${PG_PORT}"

# ============================================================================
# 停止旧进程的函数
# ============================================================================

# 通过端口号查找并停止占用该端口的进程
kill_by_port() {
  local port="$1"
  local pids
  
  # lsof -ti tcp:port 列出占用指定 TCP 端口的进程 PID
  # || true 防止没有进程时报错导致脚本退出（因为 set -e）
  pids="$(lsof -ti tcp:"${port}" || true)"
  
  if [[ -n "${pids}" ]]; then
    echo "Stopping process(es) on port ${port}: ${pids}"
    # 先尝试优雅终止（发送 SIGTERM）
    kill ${pids} || true
    sleep 1
    
    # 再次检查，如果进程还在，强制杀死（发送 SIGKILL）
    pids="$(lsof -ti tcp:"${port}" || true)"
    if [[ -n "${pids}" ]]; then
      echo "Force killing process(es) on port ${port}: ${pids}"
      kill -9 ${pids} || true
    fi
  else
    echo "No process found on port ${port}"
  fi
}

# ============================================================================
# 停止现有服务
# ============================================================================

# 停止 Docker 方式运行的 Redis
if [[ "${REDIS_MODE}" == "docker" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if docker_container_exists "${REDIS_DOCKER_NAME}"; then
      echo "Stopping docker redis container: ${REDIS_DOCKER_NAME}"
      docker stop "${REDIS_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
else
  # 停止本地 Redis（使用 redis-cli 命令）
  if command -v redis-cli >/dev/null 2>&1; then
    echo "Trying redis-cli shutdown on port ${REDIS_PORT}"
    redis-cli -h 127.0.0.1 -p "${REDIS_PORT}" shutdown || true
  fi
fi

# 停止 Docker 方式运行的 PostgreSQL
if [[ "${PG_MODE}" == "docker" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if docker_container_exists "${PG_DOCKER_NAME}"; then
      echo "Stopping docker postgres container: ${PG_DOCKER_NAME}"
      docker stop "${PG_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
fi

# 停止 MinIO（如果使用 MinIO 作为存储后端）
if [[ "${INGEST_FILE_STORAGE_BACKEND}" == "minio" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if docker_container_exists "${MINIO_DOCKER_NAME}"; then
      echo "Stopping docker minio container: ${MINIO_DOCKER_NAME}"
      docker stop "${MINIO_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
fi

# 通过端口号停止业务服务进程
kill_by_port "${JAVA_PORT}"
kill_by_port "${DOCREADER_PORT}"
rm -f "${APP_PID_FILE}" "${DOCREADER_PID_FILE}"

# 如果是本地模式的 Redis/PostgreSQL，也需要停止端口上的进程
if [[ "${REDIS_MODE}" != "docker" ]]; then
  kill_by_port "${REDIS_PORT}"
fi
if [[ "${PG_MODE}" != "docker" ]]; then
  kill_by_port "${PG_PORT}"
fi

# ============================================================================
# 启动服务
# ============================================================================

log "Starting services"

# ----------------------------------------------------------------------------
# 启动 Redis
# ----------------------------------------------------------------------------
if docker_ready; then
  if [[ "${REDIS_MODE}" == "docker" ]]; then
    if docker_container_exists "${REDIS_DOCKER_NAME}"; then
      # 容器已存在，直接启动或重启
      echo "Starting existing redis container: ${REDIS_DOCKER_NAME}"
      docker start "${REDIS_DOCKER_NAME}" >/dev/null || docker restart "${REDIS_DOCKER_NAME}" >/dev/null
    else
      # 容器不存在，拉取镜像并创建新容器
      echo "Pulling redis image: redis:7"
      docker pull redis:7 >/dev/null
      echo "Creating redis container: ${REDIS_DOCKER_NAME}"
      # -d: 后台运行
      # --restart unless-stopped: 除非手动停止，否则自动重启
      # -p host_port:container_port: 端口映射
      docker run -d --name "${REDIS_DOCKER_NAME}" --restart unless-stopped -p "${REDIS_PORT}:6379" redis:7 >/dev/null
    fi
  elif command -v redis-server >/dev/null 2>&1; then
    # 本地模式：使用 brew services 或直接启动
    if command -v brew >/dev/null 2>&1; then
      echo "Starting redis via brew services"
      brew services start redis || true
    else
      echo "Starting redis-server directly"
      # nohup: 即使关闭终端也继续运行
      # & : 后台运行
      nohup redis-server --port "${REDIS_PORT}" > "${LOG_DIR}/redis.log" 2>&1 &
    fi
  else
    echo "redis-server not found; skipping redis start"
  fi
else
  # Docker 不可用的情况
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

# ----------------------------------------------------------------------------
# 启动 MinIO（如果配置使用 MinIO 作为存储后端）
# ----------------------------------------------------------------------------
if [[ "${INGEST_FILE_STORAGE_BACKEND}" == "minio" ]]; then
  if ! docker_ready; then
    echo "Docker is required for INGEST_FILE_STORAGE_BACKEND=minio."
    exit 1
  fi
  if docker_container_exists "${MINIO_DOCKER_NAME}"; then
    echo "Starting existing minio container: ${MINIO_DOCKER_NAME}"
    docker start "${MINIO_DOCKER_NAME}" >/dev/null || docker restart "${MINIO_DOCKER_NAME}" >/dev/null
  else
    echo "Pulling minio image: ${MINIO_DOCKER_IMAGE}"
    docker pull "${MINIO_DOCKER_IMAGE}" >/dev/null
    echo "Creating minio container with volume ${MINIO_DOCKER_VOLUME}: ${MINIO_DOCKER_NAME}"
    # 创建持久化数据卷
    docker volume create "${MINIO_DOCKER_VOLUME}" >/dev/null
    # 启动 MinIO 容器
    # -e: 设置环境变量（用户名密码）
    # -v: 挂载数据卷
    # server /data: 指定数据目录
    # --console-address: Web 控制台地址
    docker run -d --name "${MINIO_DOCKER_NAME}" \
      --restart unless-stopped \
      -e MINIO_ROOT_USER="${MINIO_ACCESS_KEY}" \
      -e MINIO_ROOT_PASSWORD="${MINIO_SECRET_KEY}" \
      -p "${MINIO_PORT}:9000" \
      -p "${MINIO_CONSOLE_PORT}:9001" \
      -v "${MINIO_DOCKER_VOLUME}:/data" \
      "${MINIO_DOCKER_IMAGE}" server /data --console-address ":9001" >/dev/null
  fi
fi

# ----------------------------------------------------------------------------
# 启动 PostgreSQL
# ----------------------------------------------------------------------------
if [[ "${PG_MODE}" == "docker" ]]; then
  if ! docker_ready; then
    echo "Docker is required for PG_MODE=docker."
    echo "Set PG_MODE=local and provide DB_URL/DB_USER/DB_PASSWORD for local postgres."
    exit 1
  fi
  if docker_container_exists "${PG_DOCKER_NAME}"; then
    echo "Starting existing postgres container: ${PG_DOCKER_NAME}"
    docker start "${PG_DOCKER_NAME}" >/dev/null || docker restart "${PG_DOCKER_NAME}" >/dev/null
  else
    echo "Pulling postgres image: ${PG_DOCKER_IMAGE}"
    docker pull "${PG_DOCKER_IMAGE}" >/dev/null
    echo "Creating postgres container with volume ${PG_DOCKER_VOLUME}: ${PG_DOCKER_NAME}"
    docker volume create "${PG_DOCKER_VOLUME}" >/dev/null
    docker run -d --name "${PG_DOCKER_NAME}" \
      --restart unless-stopped \
      -e POSTGRES_DB="${PG_DB}" \
      -e POSTGRES_USER="${PG_USER}" \
      -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
      -p "${PG_PORT}:5432" \
      -v "${PG_DOCKER_VOLUME}:/var/lib/postgresql/data" \
      "${PG_DOCKER_IMAGE}" >/dev/null
  fi
elif [[ -z "${DB_URL:-}" || -z "${DB_USER:-}" || -z "${DB_PASSWORD:-}" ]]; then
  # 本地模式需要提供数据库连接信息
  # ${VAR:-} 语法：如果 VAR 未设置，则替换为空字符串（避免 set -u 报错）
  echo "PG_MODE=local requires DB_URL, DB_USER and DB_PASSWORD."
  echo "Example:"
  echo "  PG_MODE=local DB_URL=jdbc:postgresql://127.0.0.1:5432/agentic_rag DB_USER=agentic DB_PASSWORD=agentic ./scripts/restart_all.sh"
  exit 1
fi

# ----------------------------------------------------------------------------
# 启动 docreader（Python 文档解析服务）
# ----------------------------------------------------------------------------
log "Starting docreader_service (port ${DOCREADER_PORT})"
log "docreader python: ${DOCREADER_PYTHON_BIN}"
ensure_docreader_dependency

# 使用子 shell ( ) 启动服务，避免污染当前 shell 的环境变量
(
  cd "${ROOT_DIR}/docreader_service"
  
  # 设置 MinIO 相关环境变量
  export MINIO_ENDPOINT="${MINIO_ENDPOINT_DOCREADER}"
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY}"
  export MINIO_SECRET_KEY="${MINIO_SECRET_KEY}"
  export MINIO_BUCKET="${MINIO_BUCKET}"
  export MINIO_SECURE="${MINIO_SECURE}"
  export DOCREADER_CALLBACK_SECRET="${DOCREADER_CALLBACK_SECRET:-}"
  export DOCREADER_SIGNATURE_HEADER="${DOCREADER_SIGNATURE_HEADER:-X-Docreader-Signature}"
  export DOCREADER_TIMESTAMP_HEADER="${DOCREADER_TIMESTAMP_HEADER:-X-Docreader-Timestamp}"
  export DOCREADER_CALLBACK_TIMEOUT_SECONDS="${DOCREADER_CALLBACK_TIMEOUT_SECONDS:-120}"
  export DOCREADER_CALLBACK_RETRY_MAX="${DOCREADER_CALLBACK_RETRY_MAX:-3}"
  
  # 使用 uvicorn 启动 FastAPI 应用
  # main:app 表示 main.py 文件中的 app 对象
  # --host 0.0.0.0 允许外部访问
  nohup "${DOCREADER_PYTHON_BIN}" -m uvicorn main:app --host 0.0.0.0 --port "${DOCREADER_PORT}" > "${DOCREADER_LOG_FILE}" 2>&1 &
  echo $! > "${DOCREADER_PID_FILE}"
)

# ----------------------------------------------------------------------------
# 启动 agentic-rag-app（Java 业务服务）
# ----------------------------------------------------------------------------
log "Starting agentic_rag_app (port ${JAVA_PORT})"

(
  cd "${ROOT_DIR}/agentic_rag_app"
  
  # 加载 .env 文件中的环境变量（如果存在）
  # set -a: 自动导出所有变量（相当于自动 export）
  # set +a: 取消自动导出
  if [[ -f "${DOTENV_PATH}" ]]; then
    set -a
    # shellcheck disable=SC1090  # 禁用 shellcheck 对动态 source 的警告
    . "${DOTENV_PATH}"
    set +a
  fi
  
  # 设置 DOTENV_DIR 供 Spring Boot 应用读取 .env 文件
  export DOTENV_DIR="${ROOT_DIR}"
  
  # ========================================
  # Java 环境检测和配置
  # ========================================
  
  # 检查当前 JAVA_HOME 是否指向 Java 17+
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    current_java_major="$(java_major "${JAVA_HOME}/bin/java" || echo 0)"
  else
    current_java_major=0
  fi
  
  # 如果当前 Java 版本低于 17，尝试自动查找 Java 17+
  if [[ "${current_java_major}" -lt 17 ]]; then
    # macOS 特有: /usr/libexec/java_home 可以查找已安装的 Java 版本
    if [[ -x "/usr/libexec/java_home" ]]; then
      detected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
      if [[ -n "${detected_java_home}" ]]; then
        JAVA_HOME="${detected_java_home}"
      fi
    fi
  fi
  
  # 如果 JAVA_HOME 仍然无效，使用默认路径
  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
  fi
  
  # 最终验证 Java 环境
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
  
  # 导出 Java 环境变量
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
  
  # ========================================
  # 设置应用环境变量
  # ========================================
  
  # 服务端口
  export SERVER_PORT="${JAVA_PORT}"
  
  # Redis 配置
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export REDIS_PORT="${REDIS_PORT}"
  
  # 数据库配置（Docker 模式自动设置）
  if [[ "${PG_MODE}" == "docker" ]]; then
    export DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}}"
    export DB_USER="${DB_USER:-${PG_USER}}"
    export DB_PASSWORD="${DB_PASSWORD:-${PG_PASSWORD}}"
  fi
  
  # 存储配置
  export INGEST_ASYNC_ENABLED="${INGEST_ASYNC_ENABLED:-true}"
  export INGEST_FILE_STORAGE_BACKEND="${INGEST_FILE_STORAGE_BACKEND}"
  export INGEST_FILE_ROOT="${INGEST_FILE_ROOT:-/tmp/knowledge-files-local}"
  
  # MinIO 配置
  export MINIO_ENDPOINT="${MINIO_ENDPOINT_APP}"
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY}"
  export MINIO_SECRET_KEY="${MINIO_SECRET_KEY}"
  export MINIO_BUCKET="${MINIO_BUCKET}"
  export MINIO_SECURE="${MINIO_SECURE}"
  
  # Docreader 配置
  export DOCREADER_BASE_URL="${DOCREADER_BASE_URL:-http://127.0.0.1:${DOCREADER_PORT}}"
  export DOCREADER_CALLBACK_BASE_URL="${DOCREADER_CALLBACK_BASE_URL:-http://127.0.0.1:${JAVA_PORT}}"
  export DOCREADER_CALLBACK_SECRET="${DOCREADER_CALLBACK_SECRET:-}"
  
  # LLM API Keys
  export OPENAI_API_KEY="${OPENAI_API_KEY:-}"
  export MINIMAX_API_KEY="${MINIMAX_API_KEY:-}"
  export SILICONFLOW_API_KEY="${SILICONFLOW_API_KEY:-}"

  # 先用当前 JAVA_HOME 做一次干净编译，避免 target/classes 中残留更高版本 JDK 编译产物
  # 导致 spring-boot:run 在 Java 17 下报 UnsupportedClassVersionError（class file version 65.0）。
  log "Java runtime: $("${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"
  log "Compiling agentic_rag_app with JAVA_HOME=${JAVA_HOME} ..."
  if ! ./mvnw -q -DskipTests clean compile > "${APP_BUILD_LOG_FILE}" 2>&1; then
    log "Build failed. See ${APP_BUILD_LOG_FILE}"
    exit 1
  fi
  
  # 使用 Maven Wrapper 启动 Spring Boot
  # -q: 安静模式，减少输出
  # spring-boot:run: Maven 插件目标，直接运行应用（不需要先打包）
  nohup ./mvnw -q spring-boot:run > "${APP_LOG_FILE}" 2>&1 &
  echo $! > "${APP_PID_FILE}"
)

if [[ -f "${DOCREADER_PID_FILE}" ]]; then
  log "docreader pid: $(cat "${DOCREADER_PID_FILE}")"
fi
if [[ -f "${APP_PID_FILE}" ]]; then
  log "app(maven wrapper) pid: $(cat "${APP_PID_FILE}")"
fi

if ! wait_for_port "docreader_service" "${DOCREADER_PORT}" "${STARTUP_TIMEOUT_SEC}"; then
  tail_log_file "${DOCREADER_LOG_FILE}" 120
  exit 1
fi
if ! wait_for_http_ok "docreader_service" "http://127.0.0.1:${DOCREADER_PORT}/healthz" 20; then
  tail_log_file "${DOCREADER_LOG_FILE}" 120
  exit 1
fi

if ! wait_for_port "agentic_rag_app" "${JAVA_PORT}" "${STARTUP_TIMEOUT_SEC}"; then
  tail_log_file "${APP_BUILD_LOG_FILE}" 120
  tail_log_file "${APP_LOG_FILE}" 120
  if grep -q "UnsupportedClassVersionError" "${APP_LOG_FILE}" 2>/dev/null; then
    log "Detected UnsupportedClassVersionError. This means class files were compiled by a higher JDK."
  fi
  exit 1
fi

if ! wait_for_http_ok "agentic_rag_app" "http://127.0.0.1:${JAVA_PORT}/actuator/health" 20; then
  log "Warning: /actuator/health check failed, but port ${JAVA_PORT} is open."
  tail_log_file "${APP_LOG_FILE}" 80
fi

log "Done. Logs:"
log "  ${REDIS_LOG_FILE}"
log "  ${DOCREADER_LOG_FILE}"
log "  ${APP_BUILD_LOG_FILE}"
log "  ${APP_LOG_FILE}"
