# 第一阶段：冻结记忆语义与分层边界

## 本阶段目标

第一阶段只做“语义定桩 + 最小代码矫正”，不提前拆执行上下文，不改 markdown block 格式，也不引入 `memory_get`。

这一阶段的目标是先把记忆系统里几个容易混在一起的职责钉住：

- 聊天持久化记录：继续由 `PersistentMessageStore` 承担
- 跨请求 session working context：继续由 `ContextManager` 承担
- 单次 turn execution context：继续由 agent loop 内的 `localContext` 承担
- 长期记忆写入：继续由 `MemoryFlushService` 单独承担

## 语义定稿

- `memoryEnabled=false` 在 agent 流式路径上只关闭“长期记忆能力”，不等于关闭多轮会话连续性
- `memoryEnabled=false` 时：
  - 不注入 memory prompt
  - 不暴露 `memory_search`
  - 不触发 session switch flush
  - 不触发 pre-compaction durable flush
- `memoryEnabled=false` 时，session context 仍然照常追加 user / assistant / tool 轨迹，供后续多轮请求继续使用
- `ContextManager.addMessage()` 在第一阶段仍保留 overflow 后的压缩行为，但这里只把它定义为“过渡期 session context 容量保护”，不是正式 preflight compact

## 代码触点

- `chat/context/ContextManager`
  - 新增 `addMessage(String sessionId, ChatMessage message, SessionContextAppendOptions options)` 重载
  - 原双参方法保留，并委托到默认 options
- `chat/context/SessionContextAppendOptions`
  - 新增轻量值对象
  - 第一阶段只包含 `allowPreCompactionFlush`
- `chat/context/InMemorySessionContextManager`
  - 追加消息与 overflow 容量保护仍在这里
  - 是否触发 `flushPreCompaction()` 由 `SessionContextAppendOptions` 控制
- `agent/AgentStreamingService`
  - 新增统一的 session-context 写回 helper
  - user / assistant / tool-call / tool-result 的 session context 回写全部收口到同一处
  - `memoryEnabled=false` 时改为写 session context 但不触发 pre-compaction flush

## 测试结果

本阶段新增并通过了以下验证：

- `InMemorySessionContextManagerOverflowTest`
  - 默认 options 下，overflow 仍会触发 `flushPreCompaction()`
  - `allowPreCompactionFlush=false` 下，overflow 仍会压缩，但不会调用 `MemoryFlushService`
- `AgentStreamingServiceTest`
  - `memoryEnabled=false` 时，agent 不会触发 `flushPreCompaction()`
  - 即使关闭长期记忆，session context 仍会保留 `USER / TOOL_CALL / TOOL_RESULT / ASSISTANT` 轨迹

本阶段执行过的测试：

- `./mvnw -Dtest=InMemorySessionContextManagerOverflowTest,AgentStreamingServiceTest,SessionManagerIsolationTest,MemoryPromptContributorTest,RegisteredToolsPromptContributorTest test`
- `./mvnw clean test`

## 遗留到 Stage 2/3 的事项

- Stage 2 再正式拆开 session working context 和 turn 内 execution context，避免当前 `ContextManager` 继续承载过多职责
- Stage 3 再实现真正的 preflight compact，把“读前压缩”的正式链路从当前的 overflow 保护逻辑中分离出来
- `SessionManager.delete()` 仍保留 legacy `flushOnSessionReset()`，等后续 session archive / durable flush 双通道一起重构
- `/api/llm/*/stream`、`ToolController`、`ContextDebugController` 仍使用默认 append options，暂不纳入这一阶段
