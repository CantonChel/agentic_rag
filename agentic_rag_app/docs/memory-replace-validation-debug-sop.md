# 记忆替换测试、联调与排障 SOP

## 1. 文档目标与适用对象

这份 SOP 给三类人用：

- 测试同事
- 联调同事
- 排障同事

它不只回答“怎么测”，还回答“这一步背后哪段工程实现应该在工作、出问题应该先去哪里看”。

## 2. 工程实现总览

### 2.1 durable fact replace 主链路

durable fact replace 的核心服务链路是：

1. `MemoryLifecycleOrchestrator`
2. `DailyDurableFlushService`
3. `MemoryLlmExtractor`
4. `MemoryFactKeyFactory`
5. `MemoryBlockParser`
6. `MemoryFileWatchService`
7. `MemoryIndexSyncService`

职责分工如下：

- `MemoryLifecycleOrchestrator`
  - 负责在合适时机调用 durable fact flush 和 session summary 写入
  - 如果这里没被触发，后面整条 durable fact 链路都不会发生
- `DailyDurableFlushService`
  - durable fact replace 的主执行器
  - 负责抽取、分 bucket、候选查找、判定、文件重写
- `MemoryLlmExtractor`
  - 第一次调用负责抽取结构化 facts
  - 第二次调用负责输出 `ADD / UPDATE / NONE`
- `MemoryFactKeyFactory`
  - 负责计算 `fact_key`
  - 同一 `fact_key` 是否稳定，是 replace 能否成立的关键
- `MemoryBlockParser`
  - 负责解析已有 markdown blocks 和重新渲染 block
  - 文件层的物理替换离不开它
- `MemoryFileWatchService`
  - 负责把 `.md` 文件变化转成 scope 脏标记
- `MemoryIndexSyncService`
  - 负责删旧 chunk、重建 chunk、刷新索引

### 2.2 summary 写入链路

`/new`、`/reset` summary 的核心链路是：

1. `MemoryLifecycleOrchestrator.archiveSession(...)`
2. `SessionArchiveService`
3. `MemoryLlmExtractor.generateSessionSummary(...)`

注意点：

- 当前 summary 输入来自 session projection
- 不是从 transcript archive 重建
- 当 LLM summary 为空时，`SessionArchiveService` 会 fallback 到 projected lines 生成 bullet summary

### 2.3 session_context 快照链路

`session_context` 的核心链路是：

1. `InMemorySessionContextManager`
2. `SessionContextSnapshotStore`
3. `session_context_messages`

职责分工：

- `InMemorySessionContextManager`
  - 进程内热缓存
  - cache miss 时从 snapshot store 恢复
- `SessionContextSnapshotStore`
  - 负责整份快照覆盖写入、读取、删除、枚举摘要
- `session_context_messages`
  - 数据库存储当前 projection 快照

### 2.4 memory 可视化链路

可视化和排障常用的接口与入口是：

- `MemoryScopeController`
- `GET /api/memory/scopes`
- `GET /api/memory/session-context`
- `memory.html`
- `chat.html` 的“记忆总览”跳转

## 3. 标准验证清单

每次做功能回归或联调，至少覆盖下面 6 个场景：

1. 首次写入 durable fact
2. 同一 `fact_key` 触发 replace
3. `NONE` 不写入
4. `/reset` 生成 `session_summary`
5. 重启后 `session_context` 恢复
6. `memory_search` / `memory_get` 正常召回

如果这 6 个场景都通过，基本可以认为：

- durable fact replace 正常
- summary 正常
- session context snapshot 正常
- 前端可视化正常
- 索引链路正常

## 4. 联调 SOP

下面每个场景都按统一模板写：触发方式、工程链路、文件或数据库变化、接口表现、失败时优先排查点。

### 4.1 场景一：首次写入 durable fact

触发方式：

- 用一个新用户、新 session 发起至少两轮真实对话
- 第一轮最好带一个明确的 durable 偏好或项目事实
- 第二轮再追问一次，让 preflight flush 更容易触发

预期经过的工程链路：

- `MemoryLifecycleOrchestrator`
- `DailyDurableFlushService`
- `MemoryLlmExtractor.extractDurableFacts(...)`
- `DailyDurableFlushService.upsertBucketFacts(...)`
- `MemoryFileWatchService`
- `MemoryIndexSyncService`

预期文件或数据库变化：

- `memory/users/<userId>/facts/<bucket>.md` 新增
- 文件内至少新增一个 `kind=fact` block
- block metadata 中应看到：
  - `bucket`
  - `fact_key`
  - `trigger=preflight_compact`

预期接口表现：

- `GET /api/memory/scopes?userId=<userId>&includeGlobal=true`
  - `factFiles` 出现新文件
- `GET /memory.html?userId=<userId>&sessionId=<sessionId>`
  - `facts` scope 能看到该文件
- 索引同步后，`memory_search` 可以查到这条 fact

失败时优先排查的组件：

- 没有文件：先看 `MemoryLifecycleOrchestrator` 和 `DailyDurableFlushService`
- 有文件但内容不对：看 `MemoryLlmExtractor` 和 `MemoryBlockParser`
- 文件有了但检索不到：看 `MemoryFileWatchService` 和 `MemoryIndexSyncService`

### 4.2 场景二：同一 fact_key 触发 replace

触发方式：

- 在同一用户下，先写入一条明确 fact
- 再发一轮语义相同但值更新的消息

建议示例：

- 先说：“以后都用中文回答”
- 再说：“以后都用中文回答，而且尽量简洁”

预期经过的工程链路：

- `MemoryFactKeyFactory` 生成同一个 `fact_key`
- `DailyDurableFlushService.resolveCandidateIndexes(...)` 命中旧 block
- `MemoryLlmExtractor.compareFact(...)` 返回 `UPDATE`
- `DailyDurableFlushService.rewriteFile(...)` 重写 bucket 文件

预期文件或数据库变化：

- bucket 文件路径不变
- 旧 block 被物理替换，不应长出第二条同类 block
- `block_id` 不变
- `updated_at` 刷新
- `statement` 和 `value` 更新

预期接口表现：

- `GET /api/memory/file?...`
  - 文件内容应只保留更新后的这一条
- `memory_search`
  - 检索结果应逐步反映新内容，而不是旧新同时存在

失败时优先排查的组件：

- 生成了两条重复 fact：先看 `MemoryFactKeyFactory` 与 `DailyDurableFlushService`
- 命中了旧事实但没有覆盖：看 `MemoryLlmExtractor.compareFact(...)`
- 文件内容混乱：看 `MemoryBlockParser` 与 `rewriteFile(...)`

### 4.3 场景三：NONE 不写入

触发方式：

- 在已有 durable fact 不变的前提下，再发一轮没有新增信息的表达

预期经过的工程链路：

- `MemoryLlmExtractor.compareFact(...)` 返回 `NONE`
- `DailyDurableFlushService` 不触发文件重写

预期文件或数据库变化：

- 对应 bucket 文件最后修改时间不应变化
- 文件内容不应新增 block

预期接口表现：

- `GET /api/memory/scopes`
  - `factFiles` 数量不变
- `memory_search`
  - 不应出现重复的新结果

失败时优先排查的组件：

- 明明没新信息却新增了 block：看 `compareFact(...)`
- `NONE` 了却仍然改文件：看 `DailyDurableFlushService` 的 dirty 判定

### 4.4 场景四：/reset 生成 session_summary

触发方式：

- 在一个有实际对话内容的 session 中执行 `/reset`

预期经过的工程链路：

- `MemoryLifecycleOrchestrator.archiveSession(...)`
- `SessionArchiveService.archive(...)`
- `MemoryLlmExtractor.generateSessionSummary(...)`
- 若 LLM 返回空，则 fallback summary 生效

预期文件或数据库变化：

- `memory/users/<userId>/summaries/YYYY-MM-DD-<slug>.md` 新增
- 文件内应至少有一个 `kind=session_summary` block
- `reason` 应体现 `command:/reset`

预期接口表现：

- `GET /api/memory/scopes`
  - `summaryFiles` 出现新文件
- `memory.html`
  - `summaries` scope 能看到该 summary

失败时优先排查的组件：

- 没有 summary 文件：先看 `SessionArchiveService`
- LLM 返回空导致未写入：确认 fallback 是否生效
- summary 文件有了但页面看不到：看 `MemoryScopeController`

### 4.5 场景五：重启后 session_context 恢复

触发方式：

- 先进行多轮对话，让当前 session projection 进入 `session_context_messages`
- 停服务并重启
- 重新读取该 session 的 context

预期经过的工程链路：

- 运行时写入阶段：
  - `InMemorySessionContextManager`
  - `SessionContextSnapshotStore.replaceSnapshot(...)`
- 重启恢复阶段：
  - `InMemorySessionContextManager.getContext(...)`
  - `SessionContextSnapshotStore.loadSnapshot(...)`

预期文件或数据库变化：

- `session_context_messages` 中存在该 session 的快照记录
- 重启后这些记录仍在

预期接口表现：

- `GET /api/sessions/<sessionId>/messages?userId=<userId>&contentsOnly=true`
  - 仍能拿到 projection 内容
- `GET /api/memory/session-context?userId=<userId>&sessionId=<sessionId>`
  - 能返回消息序列
- `GET /api/memory/scopes`
  - `sessionContexts` 仍能列出该 session

失败时优先排查的组件：

- 重启前有、重启后没：先看 `SessionContextSnapshotStore`
- 只在内存里有、数据库里没落下：看 `InMemorySessionContextManager`

### 4.6 场景六：memory_search / memory_get 正常召回

触发方式：

- 在 fact 或 summary 已写出的前提下，调用 `memory_search` 和 `memory_get`

预期经过的工程链路：

- `MemoryFileWatchService`
- `MemoryIndexSyncService`
- 检索与读取工具链

预期文件或数据库变化：

- 不再新增文件
- 重点看索引是否已跟上最新内容

预期接口表现：

- `memory_search`
  - 能命中新写入的 `fact` 或 `session_summary`
- `memory_get`
  - 能按路径和行号读取准确内容

失败时优先排查的组件：

- 文件有了但搜索不到：优先看 `MemoryIndexSyncService`
- search 能找到但 get 读不到：看 path、line range 和文件内容本身

## 5. 关键观察点与实现落点

### 5.1 文件层观察点

重点看下面两个目录：

- `memory/users/<userId>/facts/`
- `memory/users/<userId>/summaries/`

如果 durable fact replace 正常：

- `facts/*.md` 会新增或被重写
- `summaries/*.md` 会在 `/new`、`/reset` 后新增

对应工程实现：

- 文件写入和替换：`DailyDurableFlushService`
- block 渲染：`MemoryBlockParser`
- summary 写入：`SessionArchiveService`

### 5.2 数据库层观察点

`session_context` 重点看 `session_context_messages`。

如果 session context snapshot 正常：

- 每个 session 当前 projection 都有一组当前快照记录
- `replaceContext(...)` 后旧索引不会残留

对应工程实现：

- 快照覆盖写：`SessionContextSnapshotStore`
- 运行时写入入口：`InMemorySessionContextManager`

### 5.3 接口层观察点

重点接口：

- `GET /api/memory/scopes`
- `GET /api/memory/session-context`
- `GET /api/memory/file`
- `GET /api/sessions/{id}/messages?contentsOnly=true`

对应工程实现：

- scope 汇总与 session context 明细：`MemoryScopeController`

### 5.4 日志层观察点

索引是否跟上的最直接日志观察点是：

- `MemoryIndexSyncService`
  - `event=memory_index_sync`
  - `changedFiles`
  - `totalFiles`
  - `fullReindex`

常见判断方式：

- 文件已改但没有同步日志，多半是 watcher 没调度上
- 有同步日志但结果不对，多半是 chunk 删除或重建阶段异常

## 6. 常见故障与组件级定位

### 6.1 没写出 fact

优先看：

- `MemoryLifecycleOrchestrator`
- `DailyDurableFlushService`
- `MemoryLlmExtractor`

常见原因：

- `memoryEnabled=false`
- 没有进入 preflight flush
- 抽取结果为空

### 6.2 写成重复 fact，没有 replace

优先看：

- `MemoryFactKeyFactory`
- `DailyDurableFlushService.resolveCandidateIndexes(...)`
- `MemoryLlmExtractor.compareFact(...)`

常见原因：

- `fact_key` 不稳定
- 候选旧事实没命中
- compare 误判成 `ADD`

### 6.3 /reset 没生成 summary

优先看：

- `SessionArchiveService`
- `MemoryLlmExtractor.generateSessionSummary(...)`

常见原因：

- 没有可用 projection
- summary 被错误地判空
- fallback summary 没生效

### 6.4 文件改了但索引没更新

优先看：

- `MemoryFileWatchService`
- `MemoryIndexSyncService`

常见原因：

- watcher 没感知到 `.md` 变化
- scope 没被标脏
- sync 执行了但 chunk 没重建成功

### 6.5 memory_search 查不到

优先看：

- `MemoryIndexSyncService`
- 文件内容本身是否已经落盘

常见原因：

- 文件还没写
- 文件写了但索引还没跟上
- 检索 query 不足以命中新内容

### 6.6 重启后 session_context 丢失

优先看：

- `InMemorySessionContextManager`
- `SessionContextSnapshotStore`

常见原因：

- 只写进了内存缓存，没落数据库
- snapshot 被误删
- `getContext()` cache miss 后没有正确从 snapshot store 恢复

## 7. 验收标准

一轮完整联调结束后，至少满足下面这些标准：

- durable fact replace 能稳定工作
- 同一 `fact_key` 更新时能发生物理替换
- `/new`、`/reset` 能写出 `session_summary`
- `session_context` 重启后可恢复
- `memory_search` / `memory_get` 不回归
- `memory.html` 能按 `global / facts / summaries / session_context` 四个 scope 展示
- `chat.html` 到 `memory.html` 的跳转不回归
- 聊天主流程不因记忆失败而中断
