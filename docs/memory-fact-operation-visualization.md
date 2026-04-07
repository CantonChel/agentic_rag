# 记忆事实修改流程可视化与审计说明

## 1. 背景

durable fact replace 负责把一次会话里的长期事实写入 `facts/` 真源 markdown。为了让 `ADD / UPDATE / NONE` 的判定过程可追踪、可回放、可在前端可视化，本次实现新增了独立审计表 `memory_fact_operation_logs`，并把记忆页的 `fact` 文件详情升级成：

- 上区块：当前 md 真源
- 下区块：该文件对应的事实修改时间线

首版只覆盖 durable fact replace 审计，不覆盖 session archive summary 写入审计，也不会把审计记录接入 `memory_search / memory_get` 检索链路。

## 2. 审计模型

### 2.1 记录时机

一次 `DailyDurableFlushService.flush(...)` 会生成一个统一的 `flush_id`。同一轮 flush 内提取出的多条 durable facts 共用这个 `flush_id`。

每条事实的记录时机如下：

- `NONE`：拿到候选和判定结果后立即落库，`write_outcome = SKIPPED_NONE`
- `ADD / UPDATE`：先完成 bucket 内存态改写，bucket 文件 rewrite 成功后落库为 `APPLIED`
- `ADD / UPDATE` rewrite 失败：记录为 `WRITE_FAILED`

### 2.2 结构字段

表名：`memory_fact_operation_logs`

固定字段如下：

| 字段 | 说明 |
| --- | --- |
| `id` | 自增主键 |
| `flush_id` | 一次 flush 的统一批次号 |
| `user_id` | 用户 ID |
| `session_id` | 会话 ID |
| `trigger` | 当前固定为 `preflight_compact` |
| `file_path` | 目标 fact 文件相对路径 |
| `bucket` | durable fact bucket |
| `decision` | `ADD / UPDATE / NONE` |
| `decision_source` | `LLM_COMPARE / DIRECT_ADD_NO_CANDIDATES` |
| `write_outcome` | `APPLIED / SKIPPED_NONE / WRITE_FAILED` |
| `candidate_count` | 本次候选事实数量 |
| `matched_block_id` | 命中的旧 block id |
| `target_block_id` | 最终写入或计划写入的目标 block id |
| `incoming_fact_json` | 新事实结构化 JSON |
| `matched_fact_json` | 命中的旧事实结构化 JSON |
| `candidate_facts_json` | 全部候选事实结构化 JSON |
| `created_at` | 审计记录时间 |

### 2.3 不记录的内容

首版明确不保存：

- 原始 LLM prompt
- 原始 LLM response 文本
- `facts/` 真源文件之外的额外副本

这样可以避免日志噪声、控制数据库体积，并保持 `facts/` markdown 仍然是唯一真源。

## 3. 后端实现

### 3.1 关键类

- `agentic_rag_app/src/main/java/com/agenticrag/app/memory/DailyDurableFlushService.java`
- `agentic_rag_app/src/main/java/com/agenticrag/app/memory/audit/MemoryFactOperationLogEntity.java`
- `agentic_rag_app/src/main/java/com/agenticrag/app/memory/audit/MemoryFactOperationLogService.java`
- `agentic_rag_app/src/main/java/com/agenticrag/app/api/MemoryFactOperationController.java`

### 3.2 flush 落库流程

durable fact replace 现在的审计流程如下：

1. `DailyDurableFlushService` 从会话上下文中提取 durable facts。
2. 为本轮 flush 生成统一 `flush_id`。
3. 对每条事实先做 bucket 内候选查找。
4. 如果候选为空，则直接判定为 `ADD`，并把 `decision_source` 记为 `DIRECT_ADD_NO_CANDIDATES`。
5. 如果候选不为空，则调用 `MemoryLlmExtractor.compareFact(...)` 做 `ADD / UPDATE / NONE` 判定，并把 `decision_source` 记为 `LLM_COMPARE`。
6. `NONE` 直接记审计，不改 markdown。
7. `ADD / UPDATE` 先更新内存中的 block 列表，再按 bucket 统一 rewrite 对应 `.md` 文件。
8. rewrite 成功则落库 `APPLIED`，失败则落库 `WRITE_FAILED`。

### 3.3 查询 API

接口：

```http
GET /api/memory/fact-operations?userId=anonymous&path=memory/users/anonymous/facts/project.policy.md&limit=50
```

规则：

- 只允许读取当前用户白名单内的 `fact` 文件
- `summary / global / session_context` 文件不支持该接口
- 返回值按 `createdAt desc, id desc` 排序，前端再按 `flushId` 分组

返回字段：

- `flushId`
- `createdAt`
- `decision`
- `decisionSource`
- `writeOutcome`
- `trigger`
- `sessionId`
- `filePath`
- `bucket`
- `candidateCount`
- `matchedBlockId`
- `targetBlockId`
- `incomingFact`
- `matchedFact`
- `candidateFacts`

## 4. 前端展示

页面：`agentic_rag_app/src/main/resources/static/memory.html`

当前行为：

- 左侧 scope 导航不变，仍然按 `global / facts / summaries / session_context` 分组
- 选中 `fact` 文件时，右侧显示双区块详情
- 选中 `summary / global / session_context` 时，沿用原有详情展示

`fact` 文件详情布局：

- 上区块展示当前 markdown 真源
- 下区块展示该文件的“事实修改时间线”

时间线交互：

- 按 `flushId` 分组
- 每组显示触发时间、`sessionId`、`trigger`
- 每条卡片显示 `ADD / UPDATE / NONE`
- 每条卡片显示 `APPLIED / SKIPPED_NONE / WRITE_FAILED`
- 卡片正文展示新事实摘要、旧事实摘要、候选数、命中 block、写入 block
- 展开后展示 `incomingFact / matchedFact / candidateFacts` 的结构化 JSON

空态与错误态：

- 当前文件无审计记录时，显示明确空态
- 时间线接口失败时，仅下区块显示错误态，上区块仍然保留 markdown 真源

## 5. 真实案例跑法

推荐使用独立演示用户，避免污染已有 `anonymous` 演示数据。

推荐用户：

- `userId = memory-op-demo`
- `sessionId = memory-op-demo-1`

### 5.1 第一轮聊天：注入稳定事实并撑过双阈值

通过聊天页或 `/api/agent/minimax/stream` 发送一段较长输入，明确包含 durable facts，例如：

```text
请记住以下长期信息，后续开发都按这个来：
1. 我希望你默认用中文回答。
2. 这个项目所有说明文档都要放到仓库根目录 docs 下。
3. 当前项目要求先保证记忆链路能跑通，再做界面可视化。
4. 这次排期里，优先级最高的是把记忆替换流程做成可审计、可展示。
5. 我们做迭代时要保留已有功能，不要把 memory_search 和 memory_get 污染进去。
6. 请结合这些事实，顺手把下面这段长内容也一并消化……
（后面补足足够长的项目背景，让 session context 触达双阈值）
```

目标是让 session context 足够长，以便后续 preflight compact 时触发 durable flush。

### 5.2 第二轮聊天：触发 preflight compact

再发送一条短消息，例如：

```text
继续，我们开始看记忆页。
```

这一步会触发 preflight compact，进而触发 durable fact replace。

### 5.3 验证点

检查以下文件是否出现 durable facts：

- `memory/users/memory-op-demo/facts/user.preference.md`
- `memory/users/memory-op-demo/facts/project.policy.md`
- `memory/users/memory-op-demo/facts/project.reminder.md`

检查审计接口：

```http
GET /api/memory/fact-operations?userId=memory-op-demo&path=memory/users/memory-op-demo/facts/project.policy.md&limit=20
```

应能看到对应的 `flushId`、`decision`、`writeOutcome` 和结构化事实。

检查前端记忆页：

- 打开 `/memory.html?userId=memory-op-demo`
- 选中某个 `fact` 文件
- 确认右侧同时能看到 md 真源与时间线

### 5.4 构造真实 UPDATE / NONE

为了验证替换裁决，可继续发第三轮或第四轮消息：

- 构造 `UPDATE`

```text
把之前“项目所有说明文档都要放到根目录 docs 下”更新为：
对外说明放根目录 docs，下游实现细节可以放 agentic_rag_app/docs。
```

- 构造 `NONE`

```text
再次确认一下，我还是希望你默认用中文回答，这条不用改。
```

触发下一次 preflight compact 后，应能在对应文件的时间线上看到 `UPDATE` 或 `NONE`。

## 6. 验收清单

- durable fact replace 的每条事实都有结构化审计
- 同一次 flush 的多条事实共享同一 `flush_id`
- `NONE` 会记录但不改真源 md
- `WRITE_FAILED` 能在审计表中看到
- `fact` 文件详情可同时查看真源与时间线
- `summary / global / session_context` 仍保持原行为
- `memory_search / memory_get` 结果不受审计数据污染
