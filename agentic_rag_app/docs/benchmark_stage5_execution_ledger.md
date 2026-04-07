# 第五阶段：Execution Ledger 与单轮受控运行

> 历史说明：本文保留阶段性设计背景。凡涉及旧 `EvidenceUnit` 包、旧四件套 package，或把 authoring block 直接当 runtime chunk 的表述，均以历史方案处理。当前主链请以 `/Users/luolinhao/Documents/trae_projects/agentic_rag/docs/benchmark-pipeline.md` 为准。

## 目标

第五阶段在第三阶段的 build/knowledgeBase 隔离和第四阶段的 retrieval ledger 之上，再补齐“整轮执行结果”的应用侧真源。

这一阶段之后，benchmark 不需要再依赖：

- `/api/sessions/{sessionId}/messages`
- `/api/sessions/{sessionId}/replay`
- 前端 SSE 回放拼装最终答案

评测后端的读取真源切换为：

- `turn summary`
- `retrieval trace`

## 三本账的关系

### 1. build ledger

- 回答“这次评测锁定的是哪一版知识库”
- 入口身份是 `buildId`
- 真实检索隔离仍然落到 `knowledgeBaseId`

### 2. retrieval ledger

- 回答“检索链路里发生了什么”
- 记录各 stage 的命中、排序和 `context_output`
- 通过 `GET /api/benchmark/retrieval-traces` 查询

### 3. execution ledger

- 回答“这一轮最终答成了什么”
- 记录最终答案、结束原因、工具调用摘要、关联 retrieval trace
- 通过 `GET /api/benchmark/turn-summaries` 和 `GET /api/benchmark/turn-summaries/{turnId}` 查询

## 受控运行参数

本阶段给真实 `/api/agent/*/stream` 增加了以下参数：

- `buildId`
- `kbScope`
- `evalMode`
- `thinkingProfile`
- `memoryEnabled`

固定语义如下：

- `kbScope=AUTO`：优先 `buildId`，其次 `knowledgeBaseId`，否则退回旧全局检索
- `kbScope=BENCHMARK_BUILD`：必须传 `buildId`，由 app 侧解析出 `knowledgeBaseId`
- `evalMode=SINGLE_TURN`：不读取既有 session 历史，只保留当前 turn 本地上下文
- `memoryEnabled=false`：不注入 memory prompt，不暴露 `memory_search`，也不触发 memory flush
- `thinkingProfile=HIDE`：不发送 `thinking` SSE，不写 `THINKING` message

兼容性保持不变：

- 不传这些参数时，旧 agent 行为保持原样
- `/api/llm/*/stream` 仍不在本阶段范围内

## execution ledger 内容

`benchmark_turn_execution_summary` 固定记录：

- turn 身份：`turnId`、`sessionId`、`userId`、`traceId`
- 运行约束：`buildId`、`knowledgeBaseId`、`kbScope`、`evalMode`、`thinkingProfile`、`memoryEnabled`
- 模型信息：`provider`、`originModel`
- 输入输出：`userQuestion`、`finalAnswer`
- 结束信息：`finishReason`、`latencyMs`、`errorMessage`
- 工具摘要：`toolCallsJson`
- 检索引用：`retrievalTraceIdsJson`、`retrievalTraceRefsJson`

固定落账时机：

- 正常完成
- `max_iterations`
- `max_iterations_fallback`
- 工具或 provider 异常
- 客户端取消

## 查询接口

- `GET /api/benchmark/turn-summaries/{turnId}`
- `GET /api/benchmark/turn-summaries`

list 支持以下过滤：

- `sessionId`
- `buildId`
- `knowledgeBaseId`
- `traceId`
- `provider`
- `evalMode`
- `finishReason`

默认排序固定为 `createdAt DESC`。

## 本阶段闭环

本阶段已经补齐一条真实闭环测试：

1. 导入一个 benchmark build
2. 调用真实 `/api/agent/openai/stream`，显式传 `buildId`、`kbScope=BENCHMARK_BUILD`、`evalMode=SINGLE_TURN`、`memoryEnabled=false`
3. 从 SSE 中拿到 `turnId`
4. 用 `turnId` 查询 turn summary
5. 用 `traceId` 查询 retrieval traces

这样 benchmark 在第六阶段接 runner 时，后端真源已经完整可用。
