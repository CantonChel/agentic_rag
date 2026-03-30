# 第三阶段：基于 headroom 的会话上下文 preflight compact

## 本阶段目标

第三阶段把 session working context 的 preflight compact 正式化，并把正式触发时机固定到“新 turn 启动前”。

这一阶段完成后：

- agent 在初始化 `TurnExecutionContext` 之前，会先对 session context 做一次 preflight 检查
- preflight compact 只处理 session context，不处理数据库聊天记录，也不处理本轮 execution context
- `InMemorySessionContextManager.addMessage()` 不再承担长期记忆 flush 触发职责，只保留追加和最后一道容量保护

## preflight compact 触发时机

本阶段把触发链路固定为：

- `evalMode=SINGLE_TURN`：完全跳过 preflight compact
- 非 `SINGLE_TURN`：
  - 先 `ensureSystemPrompt`
  - 再执行 `SessionContextPreflightCompactor.prepareForTurn(...)`
  - 最后才用 compact 后的 session history 初始化 `TurnExecutionContext`

预警阈值从“超过 session 上限才兜底裁掉”改成了“预算上限减 headroom”：

- `context.session.max-tokens=600`
- `context.session.max-bytes=1200`
- `context.session.preflight-reserve-tokens=180`
- `context.session.preflight-reserve-bytes=360`

因此默认情况下：

- token 触发线为 `420`
- byte 触发线为 `840`

只要 session history 超过这两条线之一，就会在进入下一轮前触发 preflight compact。

## session context compact 规则

本阶段的 compact 算法固定为“按完整 turn 保留”：

- 永远保留 `SYSTEM` 在第 0 条
- 把 session history 视为一串完整 turn：`USER + ASSISTANT`
- 从最新 turn 开始向前回填
- 只保留完整 turn，不保留半轮消息
- 直到压回 `max - reserve` 目标线以内

边界规则也在本阶段定稿：

- 至少保留最近 1 个完整 turn
- 如果最近 1 个完整 turn 本身就超过目标线，也仍然保留它
- 当前阶段不引入摘要消息、不引入 markdown block、不引入 memory block schema

同时把回写方式固定为：

- `clear(sessionId)`
- `ensureSystemPrompt(...)`
- 对 compact 后的非 system 消息逐条 `addMessage(..., SessionContextAppendOptions.withoutPreCompactionFlush())`

这样可以避免 preflight compact 自己在回写时再次触发 flush。

## `memoryEnabled` 语义

Stage 3 继续保持前两阶段已经冻结的语义：

- `memoryEnabled=false` 时，session continuity 仍然保留
- `memoryEnabled=false` 时，preflight compact 仍然执行
- 但 `memoryEnabled=false` 时，preflight compact 不允许调用 `flushPreCompaction()`

因此它只关闭“长期记忆 flush 能力”，不关闭“跨轮 session working context”。

## 代码触点

本阶段新增或调整的核心代码如下：

- `chat/context/SessionContextBudgetEvaluator`
  - 统一 token / bytes 预算计算
  - 同时提供 storage budget 与 preflight budget 判断
- `chat/context/SessionContextPreflightCompactor`
  - 承担正式 preflight compact 入口
  - 负责检测、flush、compact、rewrite 和返回 turn 启动 history
- `agent/AgentStreamingService`
  - turn 启动时改为先执行 preflight，再初始化 `TurnExecutionContext`
  - turn 结束投影仍沿用 Stage 2 的 `SessionContextProjector`
- `chat/context/InMemorySessionContextManager`
  - 不再触发 `flushPreCompaction()`
  - overflow 压缩只保留为最后一道 session store 容量保护

## 测试结果

本阶段新增并通过了以下验证：

- `SessionContextPreflightCompactorTest`
  - 未超 `budget - reserve` 时，不 flush、不 rewrite
  - 超 token 阈值时，会 flush、compact、rewrite
  - 超 byte 阈值时，会 flush、compact、rewrite
  - `memoryEnabled=false` 等价 options 下，仍 compact 但绝不 flush
  - 最近 1 个完整 turn 永远保留
- `AgentStreamingServiceTest`
  - 验证 preflight compact 发生在 `TurnExecutionContext` 初始化前
  - 验证进入本轮模型上下文的是 compact 后的 session history
  - 验证 `memoryEnabled=false` 时仍 compact 但不 flush
  - 验证 `SINGLE_TURN` 时完全跳过 preflight
- `AgentFaultInjectionIntegrationTest`
  - 验证真实多轮链路下，第三轮启动前会清理掉过旧的 projected session history
- `InMemorySessionContextManagerOverflowTest`
  - 验证 overflow 仍会裁剪
  - 验证 overflow 不再触发 `flushPreCompaction()`

本阶段执行过的测试：

- `./mvnw -Dtest=SessionContextPreflightCompactorTest,AgentStreamingServiceTest,AgentFaultInjectionIntegrationTest,InMemorySessionContextManagerOverflowTest,SessionManagerIsolationTest,MemoryPromptContributorTest,RegisteredToolsPromptContributorTest test`
- `./mvnw clean test`

## 遗留到 Stage 4/5 的事项

- Stage 4 再实现 turn / loop 内的 auto compact，并在那里接你指定的 `16K reserve`
- Stage 5 再把长期记忆触发链路拆成 `daily durable flush` 和 `session archive snapshot`
- markdown block 格式、embedding cache、`memory_get` 都继续后置，不在这一阶段提前引入
