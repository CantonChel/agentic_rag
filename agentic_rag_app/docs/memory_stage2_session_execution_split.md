# 第二阶段：拆分执行上下文与会话上下文

## 本阶段目标

第二阶段的目标是把“本轮推理时真正发给模型的上下文”和“跨请求保留的 session working context”正式拆开。

这一阶段完成后：

- agent loop 内不再实时写 `ContextManager`
- `ContextManager` 只在 turn 结束时接收一次投影结果
- session context 保存“跨轮要带走的结果”
- turn execution context 保存“本轮推理过程”

## 分层变化

本阶段新增了两层内部对象：

- `TurnExecutionContext`
  - 保存本轮 `systemPrompt`
  - 保存启动时注入的历史 session messages
  - 保存本轮运行中追加的 `USER / ASSISTANT / TOOL_CALL / TOOL_RESULT`
  - 导出当前轮真正发给模型的 message 列表
- `SessionContextProjector`
  - 负责在 turn 结束后，把本轮结果投影回 session context
  - 投影规则不再散落在 `AgentStreamingService` 里

`AgentStreamingService` 的执行链路也同步调整为：

- 非 `SINGLE_TURN` 时，先从 `ContextManager` 读取历史，初始化 `TurnExecutionContext`
- loop 内只向 `TurnExecutionContext` 和 `PersistentMessageStore` 追加消息
- 每轮 `LocalExecutionContextRecorder` 记录的是 `TurnExecutionContext` 快照
- turn 结束后，再由 `SessionContextProjector` 一次性写回 session context

## 投影规则

本阶段固定投影规则如下：

- 只投影本轮 `USER`
- 只投影本轮最终 `ASSISTANT`
- 不投影 `TOOL_CALL`
- 不投影 `TOOL_RESULT`
- 不投影 `THINKING`

固定触发规则如下：

- `evalMode=SINGLE_TURN`：完全不投影
- `finishReason=stop`：投影 `USER + 最终 ASSISTANT`
- `finishReason=max_iterations_fallback`：投影 `USER + fallback ASSISTANT`
- `finishReason=max_iterations`：不投影
- `session_switched`：不投影
- provider 异常 / cancellation：不投影

同时保留 Stage 1 的语义：

- `memoryEnabled=false` 时，投影仍然发生
- 但投影写回 session context 时使用 `SessionContextAppendOptions.withoutPreCompactionFlush()`
- 因此会保留多轮 session continuity，但不会触发长期记忆 flush

## 测试结果

本阶段新增并通过了以下验证：

- `AgentStreamingServiceTest`
  - `memoryEnabled=false` 时，session context 只保留 `SYSTEM / USER / ASSISTANT`
  - 同一 turn 的 `LocalExecutionContextRecorder` 快照中仍然保留 `TOOL_CALL / TOOL_RESULT`
  - 第二轮请求启动时，读取到的历史上下文不再带 tool 轨迹
- `AgentFaultInjectionIntegrationTest`
  - `sessionMemoryStaysCleanAfterToolHeavyTurn` 现在会明确断言 session context 不包含 `TOOL_CALL / TOOL_RESULT`
- `InMemorySessionContextManagerOverflowTest`
  - 继续保证 Stage 1 的 append options 和 overflow 行为未回退

本阶段执行过的测试：

- `./mvnw -Dtest=AgentStreamingServiceTest,AgentFaultInjectionIntegrationTest,InMemorySessionContextManagerOverflowTest,SessionManagerIsolationTest,MemoryPromptContributorTest,RegisteredToolsPromptContributorTest test`
- `./mvnw clean test`

## 遗留到 Stage 3/4 的事项

- Stage 3 再实现真正的 preflight compact，压缩对象将明确是 session context，而不是 turn execution context
- Stage 4 再实现 loop 内 auto compact，压缩对象将明确是 turn execution context，而不是 session context
- 长期记忆写入通道仍未拆成 `daily durable flush` 与 `session archive snapshot`
- `StreamingChatService`、`ToolController`、`ContextDebugController` 仍未切到这套新分层
