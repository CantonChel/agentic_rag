import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Gauge } from 'k6/metrics';
import exec from 'k6/execution';

// ============================================================
// Agentic RAG 压测脚本 v3
// ============================================================
// 场景体系：
//   1. agent_backpressure — 核心：多轮工具调用 + LLM 延迟 → 背压
//   2. agent_sse_fast     — 辅助：高并发短连接 → 连接容量上限
//   3. session_crud       — 辅助：DB 连接池
//   4. rag_search         — 独立：PG 混合检索并发（需真实 PG）
//
// 核心思路：Agent 高并发的真正痛点是 LLM 思考延迟 + 多轮工具调用
// 导致长连接长时间占用线程，这才是需要背压的原因。
// agent_sse_fast 测的是另一个维度：连接容量上限。
//
// 使用方式:
//   k6 run scripts/loadtest/k6-agentic-rag.js
//   k6 run --env SCENARIO=agent_backpressure scripts/loadtest/k6-agentic-rag.js
//   k6 run --env SCENARIO=agent_sse_fast scripts/loadtest/k6-agentic-rag.js
//   k6 run --env SCENARIO=stress_rag scripts/loadtest/k6-agentic-rag.js  (需真实 PG)
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

// ============================================================
// Custom metrics
// ============================================================

// SSE 级别指标 — 真正反映用户体验
const sseDuration = new Trend('sse_duration_ms', true);         // SSE 从连接到完成总耗时
const sseEvents = new Trend('sse_events_count', true);           // 每个 SSE 流收到多少事件
const sseToolCalls = new Trend('sse_tool_calls_count', true);    // 触发了多少次工具调用
const sseErrors = new Counter('sse_errors');                     // SSE 级别错误
const activeSse = new Gauge('active_sse_connections');           // 当前并发 SSE 连接数

// Agent 循环核心指标 — 多轮工具调用的压力特征
const agentRounds = new Trend('agent_rounds', true);             // 每个请求经历了多少轮 LLM 调用
const agentThinkingTime = new Trend('agent_thinking_time_ms', true); // LLM 思考耗时（从 turn_start 到第一个 delta/tool_start）
const agentToolTime = new Trend('agent_tool_time_ms', true);     // 工具执行耗时
const agentFirstTokenTime = new Trend('agent_first_token_ms', true); // 首 token 时间（用户体感延迟）
const agentFirstEventTime = new Trend('agent_first_event_ms', true); // 首个事件时间
const agentServerTurnTime = new Trend('agent_server_turn_ms', true); // 服务端 turn_start -> turn_end
const agentQueueDelay = new Trend('agent_queue_delay_ms', true); // 请求发起 -> 服务端 turn_start

// 资源级指标
const sessionCreated = new Counter('session_created');
const sessionDeleted = new Counter('session_deleted');

// ============================================================
// Scenarios — 默认场景聚焦 Agent 多轮工具调用
// ============================================================

export const options = {
  scenarios: {
    // === 核心场景：Agent 多轮工具调用 + 真实延迟 ===
    // 这是 Agent 高并发的主要痛点：
    //   - 每轮 LLM 思考 2-5s，线程阻塞
    //   - 3 轮工具调用 = 6-15s 长连接
    //   - 50 并发 = 50 个线程同时阻塞
    //   → 这才是需要背压的原因
    agent_backpressure: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 },    // 缓爬：10 并发
        { duration: '60s', target: 30 },    // 稳压：30 并发 — 观察是否出现线程/内存问题
        { duration: '30s', target: 50 },    // 加压：50 并发 — 观察是否崩溃
        { duration: '15s', target: 0 },     // 降压
      ],
      tags: { scenario: 'agent_backpressure' },
      exec: 'agentBackpressure',
    },

    // === 辅助场景：Agent SSE 快速 Mock ===
    // 测不同维度：高并发短连接 → 连接容量上限
    // backpressure 测的是"每个连接占线程久"，这里测的是"能同时建立多少连接"
    agent_sse_fast: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 20 },
        { duration: '60s', target: 100 },
        { duration: '15s', target: 0 },
      ],
      tags: { scenario: 'agent_sse_fast' },
      exec: 'agentSseFast',
    },

    // === 辅助场景：Session CRUD — 测 DB 连接池 ===
    session_crud: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '10s', target: 0 },
      ],
      tags: { scenario: 'session_crud' },
      exec: 'sessionCrud',
    },
  },

  thresholds: {
    // Agent 背压场景：SSE 总耗时受 LLM 延迟影响，p95 在 30s 内
    // 如果 LOADTEST_LLM_DELAY_MS=2000, TOOL_ROUNDS=3 → 最少 6s
    sse_duration_ms: ['p(95)<60000'],
    // SSE 错误不应超过 5%
    sse_errors: ['count<50'],
    // HTTP 失败率 < 15%
    http_req_failed: ['rate<0.15'],
  },
};

// ============================================================
// 核心场景：Agent 多轮工具调用 + 背压
// ============================================================

export function agentBackpressure() {
  return agentSseStream({
    withTools: true,
    prompt: '请搜索知识库中关于API文档的内容，并总结关键信息',
  });
}

// ============================================================
// Session CRUD 场景
// ============================================================

export function sessionCrud() {
  const createRes = http.post(`${BASE_URL}/api/sessions?userId=loadtest`);
  check(createRes, { 'session created': (r) => r.status === 200 });
  if (createRes.status === 200) {
    sessionCreated.add(1);
  }

  let sessionId = null;
  try {
    const body = JSON.parse(createRes.body);
    sessionId = body.sessionId;
  } catch (e) {
    sseErrors.add(1);
  }

  if (sessionId) {
    const listRes = http.get(`${BASE_URL}/api/sessions?userId=loadtest`);
    check(listRes, { 'sessions listed': (r) => r.status === 200 });

    const msgRes = http.get(`${BASE_URL}/api/sessions/${sessionId}/messages?userId=loadtest`);
    check(msgRes, { 'messages ok': (r) => r.status === 200 });

    const delRes = http.del(`${BASE_URL}/api/sessions/${sessionId}?userId=loadtest`);
    check(delRes, { 'session deleted': (r) => r.status === 200 });
    if (delRes.status === 200) {
      sessionDeleted.add(1);
    }
  }

  sleep(0.5);
}

// ============================================================
// RAG Search 场景 — 需要真实 PostgreSQL（H2 不支持 tsvector）
// ============================================================

const SEARCH_QUERIES = [
  '知识库', 'API文档', '配置', '部署', '检索',
  '模型', '向量', 'embedding', 'chunk', 'rerank',
];

export function ragSearch() {
  const query = SEARCH_QUERIES[Math.floor(Math.random() * SEARCH_QUERIES.length)];
  const res = http.get(`${BASE_URL}/api/rag/search?q=${encodeURIComponent(query)}&topK=5`);
  check(res, {
    'rag search ok': (r) => r.status === 200,
  });
  sleep(0.3);
}

// ============================================================
// Core SSE stream function — 带详细事件解析
// ============================================================

function agentSseStream(opts) {
  const { withTools = false, prompt = '请介绍一下知识库中的内容' } = opts;
  const sessionId = `lt-${__VU}-${__ITER}`;

  const q = `userId=loadtest&sessionId=${encodeURIComponent(sessionId)}&prompt=${encodeURIComponent(prompt)}&tools=${withTools ? 'true' : 'false'}&toolChoice=${withTools ? 'AUTO' : 'NONE'}&memoryEnabled=false`;

  const startTime = Date.now();
  activeSse.add(1);

  // 事件级统计
  let eventCount = 0;
  let toolCallCount = 0;
  let roundCount = 0;
  let hasDone = false;
  let hasError = false;

  // 时间戳追踪 — 计算首 token 时间、思考时间等
  let turnStartTime = startTime;
  let currentRound = 0;
  let turnStartEventTs = null;
  let phaseStartEventTs = null;
  let firstNonStartEventTs = null;
  let firstDeltaEventTs = null;
  let turnEndEventTs = null;

  const url = `${BASE_URL}/api/agent/openai/stream?${q}`;
  const metricTags = { scenario: exec.scenario.name };

  try {
    const res = http.get(url, {
      headers: { Accept: 'text/event-stream' },
      timeout: '180s',  // 多轮工具调用可能需要很长时间
    });

    if (res.status !== 200) {
      sseErrors.add(1);
      hasError = true;
      check(res, { 'sse connected': (r) => r.status === 200 });
    } else {
      // Parse SSE events
      const lines = (res.body || '').split('\n');
      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const payload = line.substring(5).trim();
        if (!payload) continue;

        let event;
        try {
          event = JSON.parse(payload);
        } catch (e) {
          continue;
        }

        eventCount++;

        // 首个非 turn_start 事件 → 首 token 时间
        switch (event.type) {
          case 'thinking':
            // LLM 思考事件，记录思考时间
            if (event.roundId != null && event.roundId > currentRound) {
              if (currentRound > 0) {
                // 上一轮思考结束
              }
              currentRound = event.roundId;
            }
            break;

          case 'tool_start':
            toolCallCount++;
            if (phaseStartEventTs != null && event.ts != null) {
              agentThinkingTime.add(event.ts - phaseStartEventTs, metricTags);
            }
            break;

          case 'tool_end':
            if (event.ts != null) {
              phaseStartEventTs = event.ts;
            }
            if (event.durationMs != null) {
              agentToolTime.add(event.durationMs, metricTags);
            }
            break;

          case 'delta':
            // 第一个 delta 事件 = 首 token 到达
            break;

          case 'done':
            hasDone = true;
            roundCount = event.roundId || currentRound;
            break;

          case 'error':
            hasError = true;
            break;

          case 'turn_start':
            turnStartTime = event.ts || Date.now();
            turnStartEventTs = turnStartTime;
            phaseStartEventTs = turnStartTime;
            agentQueueDelay.add(turnStartTime - startTime, metricTags);
            break;

          case 'turn_end':
            turnEndEventTs = event.ts || null;
            break;
        }

        if (event.type !== 'turn_start' && firstNonStartEventTs === null && event.ts != null) {
          firstNonStartEventTs = event.ts;
          agentFirstEventTime.add(firstNonStartEventTs - startTime, metricTags);
        }
        if (event.type === 'delta' && firstDeltaEventTs === null && event.ts != null) {
          firstDeltaEventTs = event.ts;
          agentFirstTokenTime.add(firstDeltaEventTs - startTime, metricTags);
        }
      }

      const durationMs = Date.now() - startTime;
      sseDuration.add(durationMs, metricTags);
      sseEvents.add(eventCount, metricTags);
      if (toolCallCount > 0) {
        sseToolCalls.add(toolCallCount, metricTags);
      }
      if (roundCount > 0) {
        agentRounds.add(roundCount, metricTags);
      }
      if (turnStartEventTs != null && turnEndEventTs != null) {
        agentServerTurnTime.add(turnEndEventTs - turnStartEventTs, metricTags);
      }

      check(res, { 'sse completed': () => hasDone });
      check(res, { 'sse no error': () => !hasError });
    }
  } catch (e) {
    sseErrors.add(1);
    const durationMs = Date.now() - startTime;
    sseDuration.add(durationMs);
  } finally {
    activeSse.add(-1);
  }

  sleep(0.5);
}

// ============================================================
// Standalone scenario overrides (use with --env SCENARIO=xxx)
// ============================================================

if (__ENV.SCENARIO === 'agent_backpressure') {
  options.scenarios = {
    agent_backpressure: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 },
        { duration: '60s', target: 30 },
        { duration: '30s', target: 50 },
        { duration: '15s', target: 0 },
      ],
      exec: 'agentBackpressure',
    },
  };
}

// 快速 Mock — 低延迟无工具，测连接上限
if (__ENV.SCENARIO === 'agent_sse_fast') {
  options.scenarios = {
    agent_sse_fast: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 20 },
        { duration: '60s', target: 100 },
        { duration: '15s', target: 0 },
      ],
      exec: 'agentSseFast',
    },
  };
}

export function agentSseFast() {
  return agentSseStream({
    withTools: false,
    prompt: '请介绍一下知识库中的内容',
  });
}

// 真实工具调用 — Mock LLM + 真实 ToolRouter
if (__ENV.SCENARIO === 'agent_sse_with_tools') {
  options.scenarios = {
    agent_sse_with_tools: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 },
        { duration: '60s', target: 50 },
        { duration: '15s', target: 0 },
      ],
      exec: 'agentSseWithTools',
    },
  };
}

export function agentSseWithTools() {
  return agentSseStream({
    withTools: true,
    prompt: '请搜索知识库中关于API文档的内容',
  });
}

// 慢速 Mock — 模拟真实 LLM 5s 延迟
if (__ENV.SCENARIO === 'agent_sse_slow') {
  options.scenarios = {
    agent_sse_slow: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 10 },
        { duration: '120s', target: 30 },
        { duration: '20s', target: 0 },
      ],
      exec: 'agentSseSlow',
    },
  };
}

export function agentSseSlow() {
  return agentSseStream({
    withTools: true,
    prompt: '请搜索知识库中关于API文档的内容，并总结关键信息',
  });
}

// LLM 卡死 — 测超时和资源泄漏
if (__ENV.SCENARIO === 'agent_sse_stuck') {
  options.scenarios = {
    agent_sse_stuck: {
      executor: 'constant-vus',
      vus: 20,
      duration: '60s',
      exec: 'agentSseStuck',
    },
  };
  options.thresholds = {
    sse_duration_ms: [],
    sse_errors: [],
  };
}

export function agentSseStuck() {
  return agentSseStream({
    withTools: false,
    prompt: '测试卡死场景',
  });
}

// Session 极限压测
if (__ENV.SCENARIO === 'stress_session') {
  options.scenarios = {
    stress_session: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '60s', target: 200 },
        { duration: '10s', target: 0 },
      ],
      exec: 'sessionCrud',
    },
  };
}

// RAG Search 极限压测 — 需要真实 PostgreSQL
if (__ENV.SCENARIO === 'stress_rag') {
  options.scenarios = {
    stress_rag: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '60s', target: 100 },
        { duration: '10s', target: 0 },
      ],
      exec: 'ragSearch',
    },
  };
}

// ============================================================
// Report
// ============================================================

export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const reportFile = `scripts/loadtest/reports/loadtest-report-${timestamp}.json`;

  return {
    stdout: textSummary(data),
    [reportFile]: JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  let lines = [];
  lines.push('\n========== Agentic RAG 压测报告 ==========\n');
  lines.push('核心关注：Agent 多轮工具调用的线程/内存压力');
  lines.push('痛点：LLM 思考延迟 + 长连接占用线程 → 需要背压\n');

  for (const [name, scenario] of Object.entries(data.scenarios || {})) {
    lines.push(`\n--- 场景: ${name} ---`);
    const metrics = scenario.metrics || {};

    // 基础 HTTP 指标
    if (metrics.http_req_duration) {
      lines.push(`  HTTP 延迟: avg=${fmtMs(metrics.http_req_duration.avg)} p95=${fmtMs(metrics.http_req_duration.values?.['p(95)'] || 0)}`);
    }

    // SSE 核心指标
    if (metrics.sse_duration_ms) {
      lines.push(`  SSE 总耗时: avg=${fmtMs(metrics.sse_duration_ms.avg)} p95=${fmtMs(metrics.sse_duration_ms.values?.['p(95)'] || 0)}`);
    }
    if (metrics.agent_first_token_ms) {
      lines.push(`  首 token 延迟: avg=${fmtMs(metrics.agent_first_token_ms.avg)} p95=${fmtMs(metrics.agent_first_token_ms.values?.['p(95)'] || 0)}`);
    }
    if (metrics.agent_thinking_time_ms) {
      lines.push(`  LLM 思考时间: avg=${fmtMs(metrics.agent_thinking_time_ms.avg)} p95=${fmtMs(metrics.agent_thinking_time_ms.values?.['p(95)'] || 0)}`);
    }
    if (metrics.agent_tool_time_ms) {
      lines.push(`  工具执行时间: avg=${fmtMs(metrics.agent_tool_time_ms.avg)} p95=${fmtMs(metrics.agent_tool_time_ms.values?.['p(95)'] || 0)}`);
    }
    if (metrics.agent_rounds) {
      lines.push(`  Agent 轮数: avg=${metrics.agent_rounds.avg?.toFixed(1)} max=${metrics.agent_rounds.max?.toFixed(0) || '?'}`);
    }
    if (metrics.sse_events_count) {
      lines.push(`  SSE 事件数: avg=${metrics.sse_events_count.avg?.toFixed(1)}`);
    }
    if (metrics.sse_tool_calls_count) {
      lines.push(`  工具调用数: avg=${metrics.sse_tool_calls_count.avg?.toFixed(1)}`);
    }
    if (metrics.sse_errors) {
      lines.push(`  SSE 错误: ${metrics.sse_errors.count || 0}`);
    }
    if (metrics.http_req_failed) {
      lines.push(`  HTTP 失败率: ${(metrics.http_req_failed.rate * 100).toFixed(1)}%`);
    }
    if (metrics.iterations) {
      lines.push(`  完成迭代: ${metrics.iterations.count}`);
    }

    // 背压诊断
    if (metrics.active_sse_connections) {
      lines.push(`  峰值并发 SSE: ${metrics.active_sse_connections.max || '?'}`);
    }
  }

  // 诊断提示
  lines.push('\n--- 背压诊断 ---');
  const sseDur = data.metrics?.sse_duration_ms;
  const sseErr = data.metrics?.sse_errors;
  if (sseDur && sseErr) {
    const p95 = sseDur.values?.['p(95)'] || 0;
    const errCount = sseErr.count || 0;
    if (p95 > 30000) {
      lines.push('  ⚠️  SSE p95 > 30s：线程可能被长连接占满，需要背压/限流');
    }
    if (errCount > 20) {
      lines.push('  ⚠️  SSE 错误 > 20：可能有连接被拒绝或超时');
    }
    if (p95 < 30000 && errCount < 20) {
      lines.push('  ✓  当前并发下系统表现正常');
    }
  }

  lines.push('\n==========================================\n');
  return lines.join('\n');
}

function fmtMs(ms) {
  if (!ms) return '0ms';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms.toFixed(0)}ms`;
}
