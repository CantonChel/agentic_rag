# 第四阶段：实现 turn 内 auto compact

## 本阶段目标

第四阶段只处理单次 turn 的执行上下文，不再修改 session context 的 preflight 逻辑，也不引入长期记忆写入新通道。

这一阶段完成后：

- Stage 3 继续负责 session working context 的 preflight compact
- Stage 4 负责 agent loop 内、每次再次调用模型前的本地 auto compact
- `PersistentMessageStore` 继续保留完整 raw trace
- 只有发给模型的临时上下文会被 compact，compact 结果不会写数据库、不会投影 session、不会进入长期记忆

## 分层变化

本阶段把 `TurnExecutionContext` 的职责固定为“本轮原始轨迹真相”：

- 持有 `systemPrompt`
- 持有 Stage 3 preflight 后注入的 session history
- 持有本轮 raw `USER / ASSISTANT / TOOL_CALL / TOOL_RESULT`
- 对外暴露：
  - `getHistoricalMessages()`
  - `getTurnMessages()`
  - `getRawMessagesForDebug()`

正式发给模型的 view 不再直接取自 `TurnExecutionContext`，而是改由 `TurnContextAutoCompactor` 每轮按需重建。

## auto compact 规则

本阶段新增独立配置：

- `agent.turn.context-window-tokens=0`
- `agent.turn.reserve-tokens=16000`
- `agent.turn.keep-recent-tokens=16000`

启用规则固定为：

- `context-window-tokens <= 0`：turn auto compact 关闭
- `context-window-tokens > 0`：turn auto compact 启用
- 触发线：`modelContextTokens > contextWindowTokens - reserveTokens`

compact 算法定稿为：

- 永远保留 system prompt
- 永远保留 preflight 后留下的 session history
- 永远保留当前 turn 的首条 `USER`
- `THINKING` 永不进入 model-facing context
- 未超触发线时，直接返回 raw model view
- 超限时，只压缩当前 turn 的较早前缀
- 最近后缀按 `keepRecentTokens` 从尾部反向保留
- 如果后缀边界落在 `TOOL_RESULT` 上，会向左补齐同一段 `TOOL_CALL`

被压缩掉的旧前缀会转成一条临时 `AssistantMessage`，格式固定为：

- 首行：`[Context Compact Summary]`
- 后续逐行保留旧前缀里的 `USER / ASSISTANT / TOOL_CALL / TOOL_RESULT` 关键信息
- 单行上限 `240 chars`
- 总行数上限 `20`
- 超出时补 `...`

这一条 summary 只存在于当前 turn 的 model-facing context，不会持久化。

## loop 接入与 fallback

`AgentStreamingService` 本阶段改成：

- 每次 while-loop 里、构造 `ChatCompletionCreateParams` 前
- 先调用 `TurnContextAutoCompactor.compactForNextModelCall(...)`
- `LocalExecutionContextRecorder.record(...)` 改记录 compact 后的 model view
- `OpenAiMessageAdapter.toMessageParams(...)` 也改走 compact 后的 messages

超限兜底规则定稿为两层：

- best-effort compact 后回到 `window - reserve` 以内：正常继续
- best-effort compact 后仍超过触发线：进入“最后一轮强制交卷”
  - 禁用工具
  - 追加 `context overflow finalization warning`
  - 强制输出最终答案
  - `done.finishReason=context_window_fallback`
- 如果最小可发送上下文（system + session history + 当前 user）本身仍超过 `context-window-tokens`
  - 直接返回受控错误 `context_window_exceeded`
  - 不调用 provider
  - 不做 session projection

同时扩展了 session projection 规则：

- `finishReason=context_window_fallback` 时，按成功收尾处理，投影 `USER + 最终 ASSISTANT`
- `context_window_exceeded` 仍不投影任何 turn 结果

## 代码触点

本阶段新增或调整的核心代码如下：

- `agent/AgentTurnContextProperties`
  - 承接 `agent.turn.*` 配置
- `chat/context/TurnContextAutoCompactor`
  - 负责 turn 内 auto compact、forced finalization 和硬溢出检测
- `chat/context/TurnContextAutoCompactResult`
  - 返回 model-facing messages、压缩标记、fallback 标记和 token 统计
- `chat/context/TurnContextWindowExceededException`
  - 表达最小上下文都无法发送的硬失败
- `chat/context/TurnExecutionContext`
  - 补齐 raw trace 访问接口
- `agent/AgentStreamingService`
  - 每次模型调用前接入 auto compact
  - recorder 改记录真实发给模型的上下文
  - 接入 `context_window_fallback` / `context_window_exceeded`
- `chat/context/SessionContextProjector`
  - 接受 `context_window_fallback` 作为可投影 finish reason

## 测试结果

本阶段新增并通过了以下验证：

- `TurnContextAutoCompactorTest`
  - 验证关闭状态下完全跳过 auto compact
  - 验证超限后会生成内部 assistant summary
  - 验证 `THINKING` 不进入 compact view
  - 验证 tool exchange 边界完整性
  - 验证 forced finalization 与 `context_window_exceeded`
- `AgentStreamingServiceTest`
  - 验证第三次模型调用前会使用 compact 后的 model view
  - 验证 recorder 记录的是 compact 后视图，不是 raw trace
  - 验证 `context_window_fallback` 时工具被禁用，session context 仍只保留 `USER + ASSISTANT`
- `AgentFaultInjectionIntegrationTest`
  - 验证 tool-heavy loop 会触发 turn 内 compact / fallback，而不是把完整工具轨迹带进 session context
  - 验证极端最小上下文超限时返回受控错误 `context_window_exceeded`

本阶段执行过的测试：

- `./mvnw -Dtest=TurnContextAutoCompactorTest,AgentStreamingServiceTest,AgentFaultInjectionIntegrationTest,SessionContextPreflightCompactorTest,InMemorySessionContextManagerOverflowTest,SessionManagerIsolationTest,MemoryPromptContributorTest,RegisteredToolsPromptContributorTest test`
- `./mvnw clean test`

## 遗留到 Stage 5 的事项

- 长期记忆正式双通道：`daily durable flush` / `session archive snapshot`
- markdown memory block 格式
- embedding cache
- `memory_get`
- `SessionManager.delete()` 等 legacy flush 触发链路的统一收口
