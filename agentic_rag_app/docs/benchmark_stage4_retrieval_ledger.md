# 第四阶段：Retrieval Ledger 与结构化检索 Sidecar

## 目标

第四阶段在第三阶段的 `knowledgeBaseId` 隔离基础上，为真实检索链路补齐结构化观测能力：

- 保留原有给模型消费的 `<context>` 字符串
- 新增给工具直调和轻量调试使用的 `sidecar`
- 新增给 benchmark 与排查系统使用的 `retrieval ledger`

这一阶段之后，评测层不再需要正则解析 `<context>` 才能知道命中了哪些 evidence。

## 三层产物

### 1. content

- 仍然是 `<context>...</context>` 文本
- 继续作为模型上下文输入
- 继续作为前端和 SSE 的兼容输出

### 2. sidecar

- 只出现在检索工具的 `ToolResult.sidecar`
- 顶层固定为 `type=retrieval_context_v1`
- 只保留最终 `context_output` 视图
- 用于轻量调试和工具直调场景

### 3. retrieval ledger

- 落库在 `benchmark_retrieval_trace`
- 记录一次真实检索调用在各 stage 的结构化结果
- 作为 benchmark 和排查的查询真源
- 通过 `GET /api/benchmark/retrieval-traces` 读取

## 为什么不能只看 context_output

只看最终 `context_output`，只能回答“模型最后看到了什么”，不能回答：

- gold evidence 有没有被 dense 召回过
- 有没有被 keyword/bm25 命中过
- 是在 hybrid fuse 被丢掉，还是在 rerank 被丢掉
- 某个阶段到底有没有执行，还是根本没跑到

因此 retrieval ledger 会保留这些 stage：

- `keyword_like`
- `bm25`
- `dense`
- `hybrid_fused`
- `reranked`
- `context_output`

## 0 命中规则

- 不记录“全库所有未命中的 chunk”
- 会记录“某个 stage 执行了，但 0 命中”的 `stage_summary`

这样可以稳定区分：

- 没有调用检索
- 调用了检索，但这一层 0 结果
- 召回过，但后续排序或裁剪丢失

## 查询接口

- `GET /api/benchmark/retrieval-traces?traceId=...`

支持可选过滤：

- `toolCallId`
- `toolName`
- `knowledgeBaseId`
- `buildId`
- `stage`
- `recordType`

返回同时包含：

- `chunk` 明细记录
- `stage_summary` 摘要记录

默认顺序为：

- `createdAt ASC`
- `toolCallId ASC`
- `stage ASC`
- `rank ASC`

## 当前边界

- 本阶段不把完整 ledger 混入 chat/SSE 主输出
- 本阶段不做 turn summary
- 本阶段不接 runner 和 RAGAS
- benchmark 侧之后应优先读取 retrieval trace API，而不是解析 `<context>`
