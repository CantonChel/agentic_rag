#!/bin/bash
set -euo pipefail

# ============================================================
# Agentic RAG 压测执行脚本 v3
# ============================================================
# 核心场景：Agent 多轮工具调用 + LLM 延迟 → 背压
#
# 用法:
#   ./scripts/loadtest/run.sh [scenario] [delay_ms]
#
# 场景:
#   default         - 默认场景：Agent 背压 + Session CRUD (Mock LLM, fake tools)
#   backpressure    - 核心场景：多轮工具调用 + 真实 LLM 延迟 (测背压)
#   real_tools      - 真实工具调用 (Mock LLM + 真实 ToolRouter)
#   fast            - 快速 Mock (低延迟, 无工具, 测连接上限)
#   slow            - 慢速 Mock (5s 延迟, 模拟真实 LLM)
#   stuck           - LLM 永不返回 (测超时/资源泄漏)
#   session         - 专项 Session CRUD 压测
#   rag             - 专项 RAG Search 压测 (需真实 PostgreSQL)
#
# 环境变量:
#   BASE_URL                    - 目标服务地址 (default: http://localhost:8081)
#   LOADTEST_LLM_DELAY_MS       - Mock LLM 延迟 (default: 2000 = 2s)
#   LOADTEST_LLM_STUCK          - 模拟 LLM 卡死 (default: false)
#   LOADTEST_AGENT_TOOL_ROUNDS  - Mock 工具轮数 (default: 3)
#   LOADTEST_TOOL_MODE          - fake/real (default: fake)
#   LOADTEST_STREAM_MODE        - final_answer_only/early_streaming (default: final_answer_only)
#
# 推荐启动方式:
#
#   场景 A — 默认压测 (Mock 2s 延迟, 3 轮 fake 工具调用):
#     LOADTEST_LLM_DELAY_MS=2000 LOADTEST_AGENT_TOOL_ROUNDS=3 \
#     java -jar app.jar --spring.profiles.active=loadtest
#
#   场景 B — 真实工具调用:
#     LOADTEST_LLM_DELAY_MS=2000 LOADTEST_AGENT_TOOL_ROUNDS=3 LOADTEST_TOOL_MODE=real \
#     java -jar app.jar --spring.profiles.active=loadtest
#
#   场景 C — 模拟真实 LLM (5s 延迟):
#     LOADTEST_LLM_DELAY_MS=5000 LOADTEST_AGENT_TOOL_ROUNDS=3 \
#     java -jar app.jar --spring.profiles.active=loadtest
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_DIR="$SCRIPT_DIR/reports"
K6_SCRIPT="$SCRIPT_DIR/k6-agentic-rag.js"

# Defaults — 2s 延迟模拟真实 LLM
SCENARIO="${1:-default}"
DELAY_MS="${2:-${LOADTEST_LLM_DELAY_MS:-2000}}"
BASE_URL="${BASE_URL:-http://localhost:8081}"
TOOL_ROUNDS="${LOADTEST_AGENT_TOOL_ROUNDS:-3}"
TOOL_MODE="${LOADTEST_TOOL_MODE:-fake}"
STREAM_MODE="${LOADTEST_STREAM_MODE:-final_answer_only}"

mkdir -p "$REPORT_DIR"

# Check k6 installed
if ! command -v k6 &>/dev/null; then
    echo "k6 未安装。请先安装:"
    echo "  brew install k6"
    echo "  或: https://k6.io/docs/get-started/installation/"
    exit 1
fi

echo "============================================"
echo "  Agentic RAG 压测 v3"
echo "============================================"
echo "  场景:       $SCENARIO"
echo "  目标:       $BASE_URL"
echo "  LLM 延迟:   ${DELAY_MS}ms"
echo "  工具轮数:   ${TOOL_ROUNDS}"
echo "  工具模式:   ${TOOL_MODE}"
echo "  流式模式:   ${STREAM_MODE}"
echo "  时间:       $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"
echo ""

# 压测前的启动提示
print_startup_hint() {
    echo ""
    echo "[提示] 确保应用已以 loadtest profile 启动:"
    echo ""
    echo "  LOADTEST_LLM_DELAY_MS=${DELAY_MS} \\"
    echo "  LOADTEST_AGENT_TOOL_ROUNDS=${TOOL_ROUNDS} \\"
    echo "  LOADTEST_TOOL_MODE=${TOOL_MODE} \\"
    echo "  LOADTEST_STREAM_MODE=${STREAM_MODE} \\"
    echo "  java -jar app.jar --spring.profiles.active=loadtest"
    echo ""
    echo "[说明] 每个请求预计耗时: ${TOOL_ROUNDS} 轮 × ${DELAY_MS}ms = ~$((TOOL_ROUNDS * DELAY_MS / 1000))s"
    echo "       30 并发 ≈ 30 个线程同时阻塞 ${DELAY_MS}ms，这才是背压要解决的问题"
    echo ""
}

case "$SCENARIO" in
    default)
        echo "[场景] 默认：Agent 背压 + Session CRUD"
        print_startup_hint
        k6 run \
            --env BASE_URL="$BASE_URL" \
            "$K6_SCRIPT"
        ;;

    backpressure)
        echo "[场景] 核心：Agent 多轮工具调用 + 背压"
        print_startup_hint
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=agent_backpressure \
            "$K6_SCRIPT"
        ;;

    real_tools)
        echo "[场景] 真实工具调用 (Mock LLM + 真实 ToolRouter)"
        TOOL_MODE="real"
        print_startup_hint
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=agent_sse_with_tools \
            "$K6_SCRIPT"
        ;;

    fast)
        echo "[场景] 快速 Mock (低延迟, 无工具, 测连接上限)"
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=agent_sse_fast \
            "$K6_SCRIPT"
        ;;

    slow)
        echo "[场景] 慢速 Mock (5s 延迟, 模拟真实 LLM)"
        DELAY_MS=5000
        print_startup_hint
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=agent_sse_slow \
            "$K6_SCRIPT"
        ;;

    stuck)
        echo "[场景] LLM 卡死 (测超时/资源泄漏)"
        echo "[提示] 确保应用已启动: LOADTEST_LLM_STUCK=true java -jar app.jar --spring.profiles.active=loadtest"
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=agent_sse_stuck \
            --timeout 180s \
            "$K6_SCRIPT"
        ;;

    session)
        echo "[场景] 专项 Session CRUD 压测"
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=stress_session \
            "$K6_SCRIPT"
        ;;

    rag)
        echo "[场景] 专项 RAG Search 压测 (需真实 PostgreSQL)"
        echo "[警告] 此场景需要真实 PostgreSQL，H2 不支持 tsvector!"
        k6 run \
            --env BASE_URL="$BASE_URL" \
            --env SCENARIO=stress_rag \
            "$K6_SCRIPT"
        ;;

    *)
        echo "未知场景: $SCENARIO"
        echo ""
        echo "可用场景:"
        echo "  default       - 默认：Agent 背压 + Session CRUD"
        echo "  backpressure  - 核心：多轮工具调用 + 背压 (推荐)"
        echo "  real_tools    - 真实工具调用 (Mock LLM + 真实 ToolRouter)"
        echo "  fast          - 快速 Mock (测连接上限)"
        echo "  slow          - 慢速 Mock (5s 延迟, 模拟真实 LLM)"
        echo "  stuck         - LLM 卡死测试"
        echo "  session       - Session CRUD 专项"
        echo "  rag           - RAG Search 专项 (需真实 PG)"
        exit 1
        ;;
esac

echo ""
echo "压测完成。报告已保存到: $REPORT_DIR/"
