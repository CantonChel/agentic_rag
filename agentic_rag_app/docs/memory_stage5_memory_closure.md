# 第五阶段：收口长期记忆写入读取闭环

## 本阶段目标

第五阶段不再继续增加新的 compact 层，而是把前四阶段已经拆开的边界正式闭环成独立的长期记忆子系统：

- 数据库继续保存原始聊天真相
- session context 继续保存跨请求的 working context
- turn context 继续承担单轮执行态
- 长期记忆只通过正式写入通道落到 markdown 文件，并只通过 `memory_search + memory_get` 读取

这一阶段完成后，项目里不再存在“legacy flush 先凑合跑、正式通道以后再说”的中间态。

## 收口后的总流程

整条链路现在固定为：

1. 新 turn 进入时，`SessionContextPreflightCompactor` 先检查 session headroom。
2. 若 session context 超过 `budget - reserve`，先由 `DailyDurableFlushService` 做一次 `daily durable flush`，再按完整 `USER + ASSISTANT` turn 回缩 session context。
3. turn 执行期间，`TurnContextAutoCompactor` 只压缩本轮发给模型的临时上下文；这一步不写数据库、不写 session、不写长期记忆。
4. turn 正常结束后，`SessionContextProjector` 仍只把本轮 `USER + 最终 ASSISTANT` 投影回 session context。
5. 遇到 session switch、`/new`、`/reset`、session delete 等生命周期事件时，由 `MemoryLifecycleOrchestrator` 统一触发 `session archive snapshot`，然后再切换或清理会话。
6. 后续 recall 不再默认读 DB transcript，而是从 `MEMORY.md`、daily durable、session archive 三类 markdown 文件里召回，再按需精读原文。

## 双通道触发归属

长期记忆写入现在被正式拆成两条正统通道：

- `daily durable flush`
  - 写入服务：`DailyDurableFlushService`
  - 正式触发方：`SessionContextPreflightCompactor`
  - 触发时机：新 turn 进入前，session preflight compact 发现超过 headroom
  - 写入内容：LLM 提炼出的 durable facts / preferences / constraints / decisions / deadlines
- `session archive snapshot`
  - 写入服务：`SessionArchiveService`
  - 协调入口：`MemoryLifecycleOrchestrator`
  - 正式触发方：session switch、`/new`、`/reset`、`SessionManager.delete()`
  - 写入内容：本次会话的 `USER / ASSISTANT` 归档快照，不包含 `TOOL_CALL / TOOL_RESULT / THINKING`

同时收死两条约束：

- `memoryEnabled=false` 时，不触发 durable flush，也不触发 archive snapshot
- `evalMode=SINGLE_TURN` 时，不参与 session preflight、不做 turn-end session projection；生命周期 archive 只在显式 session 切换或删除时触发

## block/schema 定稿

长期记忆的新写入目录固定为：

- `MEMORY.md`
- `memory/users/<userId>/daily/YYYY-MM-DD.md`
- `memory/users/<userId>/sessions/YYYY-MM-DD-<slug>.md`

所有新写入统一采用 append-only block：

- 起始行：`<!-- MEMORY_BLOCK {...} -->`
- 正文：markdown body
- 结束行：`<!-- /MEMORY_BLOCK -->`

metadata 字段定稿为：

- `schema: "memory.v1"`
- `kind: "durable" | "session_archive"`
- `block_id`
- `user_id`
- `session_id`
- `created_at`
- `trigger`
- `dedupe_key`
- archive 额外包含 `reason`
- archive 额外包含 `slug`

正文规则定稿为：

- daily durable block 只写长期有价值的信息，不直接镜像 transcript
- session archive block 只写 `USER / ASSISTANT` 轨迹
- 所有文件保持 append-only，不做原地改写

兼容策略也已经落地：

- 旧 `memory/users/<userId>/<date>.md`
- 旧 `sessions/*.md`

这些 legacy 文件继续可读，但所有新写入只会落到 `daily/` 与 `sessions/`。

去重策略固定为：

- durable block：按规范化后的 body 计算 `dedupe_key`，同日同内容不重复追加
- archive block：按 `sessionId + reason + transcript fingerprint` 做事件级幂等，避免同一生命周期事件双写

## recall 工具定稿

recall 侧现在只认 markdown memory source，不再把 DB transcript 当默认长期记忆来源。

`MemoryRecallService` 的职责分成四层：

- source discovery：只发现 `MEMORY.md`、daily、sessions
- block parser：解析 `memory.v1` block，并兼容 legacy markdown
- derived index：按文件内容 hash 重建 chunk；文件未变则直接复用内存缓存
- search/get service：分别负责候选召回与精确原文读取

embedding cache 也已收口为派生层：

- 路径：`memory/.cache/embeddings/`
- namespace：`provider / model / chunkHash`
- chunk 内容不变时复用 embedding
- provider 或 model 改变时自动进入新 namespace，不做脏复用

工具契约定稿为：

- `memory_search(query, topK)`
  - 返回候选结果
  - 每条结果包含 `path`、`kind`、`blockId`、`lines`、`score`、`snippet`
- `memory_get(path, lineStart, lineEnd)`
  - 只读取白名单范围内的 memory markdown 文件
  - 返回精确行号与原文内容

prompt 收口也已经同步：

- 同时暴露 `memory_search + memory_get` 时，系统 prompt 强制先 search 再 get
- 只暴露 `memory_search` 时，允许直接从候选回答
- 只暴露 `memory_get` 时，仅用于已知路径的精读
- `memoryEnabled=false` 时，这两类工具和对应 recall prompt 一起隐藏

## 测试结果

本阶段新增并通过了以下关键测试：

- `DailyDurableFlushServiceTest`
  - 验证 preflight 触发时只写 daily durable block
  - 验证 durable dedupe 生效
  - 验证 `memoryEnabled=false` 时不写入
- `SessionArchiveServiceTest`
  - 验证 session switch / reset / delete 只写 archive block
  - 验证同一 lifecycle event 不双写
  - 验证 archive 内容不包含 tool / thinking
- `MemoryBlockParserTest`
  - 验证 `memory.v1` block 可解析
  - 验证 legacy daily / session 文件继续可读
  - 验证行号与 `blockId` 映射稳定
- `MemorySearchServiceTest`
  - 验证只搜索 `MEMORY.md` + daily + sessions
  - 验证文件变更后按整文件重建 chunk
  - 验证未变化 chunk 可复用 embedding cache
- `MemoryGetToolTest`
  - 验证可按 path / line 精确读取
  - 验证越权路径会被拒绝
- 既有回归测试
  - `AgentStreamingServiceTest`
  - `StreamingChatServiceTest`
  - `SessionManagerIsolationTest`
  - `MemoryPromptContributorTest`
  - `RegisteredToolsPromptContributorTest`
  - `MemoryBrowseControllerTest`
  - `MemorySearchToolRegistrationTest`
  - `AgentFaultInjectionIntegrationTest`
  - `SessionContextPreflightCompactorTest`
  - `InMemorySessionContextManagerOverflowTest`

本阶段完成后执行通过的回归命令：

- `./mvnw -Dtest=DailyDurableFlushServiceTest,SessionArchiveServiceTest,MemoryBlockParserTest,MemorySearchServiceTest,MemoryGetToolTest,AgentStreamingServiceTest,AgentFaultInjectionIntegrationTest,StreamingChatServiceTest,SessionContextPreflightCompactorTest,InMemorySessionContextManagerOverflowTest,SessionManagerIsolationTest,MemoryPromptContributorTest,RegisteredToolsPromptContributorTest,MemoryBrowseControllerTest,MemorySearchToolRegistrationTest test`
- `./mvnw clean test`

## 遗留风险

当前实现已经完成正式收口，但还保留以下已知风险或后续优化点：

- `daily durable flush` 仍依赖 LLM 提炼质量；当模型输出过空或过泛时，长期记忆质量会直接受影响
- recall 的索引刷新策略是“文件 hash 变化则整文件重建”，没有做 block 级局部增量
- embedding cache 目前只按 `provider / model / chunkHash` 做 namespace，不含额外版本号；若同名模型行为发生重大变化，需要人工清 cache
- legacy markdown 目前仍允许读取，兼容逻辑会继续存在一段时间；真正彻底下线 legacy 读取前，还需要一次明确的数据迁移策略
