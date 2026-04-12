# 记忆替换实现 SOP

## 1. 背景与目标

这次记忆改造的核心目标，不是“多存一点聊天记录”，而是把长期记忆从“不断追加 archive”改成“按 durable fact 做 upsert/replace”。

要解决的问题有两个：

- 旧模式容易膨胀。相同事实只要说法稍有变化，就会不断追加。
- 旧模式不够稳定。真正需要跨轮、跨 session 保留的是 durable facts，而不是整段 transcript。

本方案的设计目标是：

- long-term memory 继续以 markdown 为真源，不把数据库变成长期记忆真源。
- durable memory 只保留 `fact` 和 `session_summary` 两类内容。
- 对同一条 durable fact，优先做 replace，而不是无限追加。
- 继续沿用现有 watcher 和异步索引链路，不额外引入复杂协调器。

## 2. 系统边界与职责划分

这套记忆系统里有三种容易混淆的存储对象，职责必须分开看。

### 2.1 long-term memory

long-term memory 真源是 markdown 文件：

- `MEMORY.md`
- `memory/users/<userId>/facts/<bucket>.md`
- `memory/users/<userId>/summaries/*.md`

其中：

- `fact` 负责保存 durable facts
- `session_summary` 负责保存 `/new`、`/reset` 触发的跨 session continuity 摘要

### 2.2 session transcript

完整会话消息仍然由 `PersistentMessageStore` 一类 transcript 存储负责。它的职责是“完整会话真源”，不是 durable fact replace 的主载体。

### 2.3 session_context

`session_context_messages` 负责保存“当前有效 session projection 快照”。它的职责是：

- 运行时恢复当前 session context
- 给 `memory.html` 的 `session_context` scope 展示使用

它不是 long-term memory 真源，也不进入 v1 的 durable fact 写入真源。

### 2.4 已退出新链路的旧语义

下面这些路径或语义，不再是当前方案的一部分：

- 旧 `daily/`
- 旧 `sessions/`
- 旧 `session archive transcript`

它们只需要在历史背景里知道存在过，不要再把它们当成现行逻辑理解。

## 3. 核心概念与术语

### 3.1 fact

`fact` 是 durable memory 的基本单元，表示“应该跨轮、跨 session 保留下来的稳定事实”。

一条 `fact` 固定包含：

- `bucket`
- `subject`
- `attribute`
- `value`
- `statement`
- `fact_key`

### 3.2 bucket

`bucket` 是 durable fact 的大类，也是物理分文件的依据。当前固定枚举是：

- `user.preference`
- `user.constraint`
- `project.policy`
- `project.decision`
- `project.constraint`
- `project.reminder`

### 3.3 fact_key

`fact_key` 的语义不是“整条自然语言事实”，而是“同一个 bucket 下，同一个主体的同一个属性”。

实现规则是：

`sha256(normalize(bucket) + "|" + normalize(subject) + "|" + normalize(attribute))`

也就是说，身份键只看三层：

- `bucket`
- `subject`
- `attribute`

而下面两项不参与身份键：

- `value`
- `statement`

### 3.4 ADD / UPDATE / NONE

durable fact 对比阶段只允许三种结果：

- `ADD`：新增一条 durable fact
- `UPDATE`：命中同一条 durable fact，物理替换旧 block
- `NONE`：没有新增信息，不写文件

## 4. 存储结构

当前 memory 存储布局如下：

```text
MEMORY.md
memory/
  users/
    <userId>/
      facts/
        user.preference.md
        user.constraint.md
        project.policy.md
        project.decision.md
        project.constraint.md
        project.reminder.md
      summaries/
        YYYY-MM-DD-<slug>.md
```

### 4.1 facts

- 每个 `bucket` 对应一个 markdown 文件
- 一个文件里可以有多条 `fact` block
- replace 只在同一个 bucket 文件里发生

### 4.2 summaries

- `/new`、`/reset` 触发时，基于 session projection 生成 `session_summary`
- 一次 summary 会写入一个新文件

### 4.3 session_context_messages

- 数据库存当前 session projection 快照
- 只负责 continuity 和恢复
- 不伪装成 markdown 文件

## 5. 主流程总览

一次 durable fact replace 的端到端主流程固定是下面 8 步：

1. 对话进入 preflight 阶段
2. LLM 抽取结构化 facts
3. 按 bucket 路由到目标文件
4. 在 bucket 文件内做候选查找
5. 判定 `ADD / UPDATE / NONE`
6. 在 md 真源层执行追加或替换
7. watcher 感知文件变化
8. 异步 file-level reindex 更新检索

可以把它理解成两段链路：

- 同步写入链路：步骤 1 到 6
- 异步索引链路：步骤 7 到 8

## 6. 主流程逐步拆解

下面是这套方案最重要的部分。读懂这一节，就基本读懂了 durable fact replace 的实现。

### 6.1 第一步：对话进入 preflight 阶段

输入：

- 当前 `scopedSessionId`
- 当前 session projection 中的消息列表
- `memoryEnabled` 开关

输出：

- 是否进入 durable fact flush

决策点：

- `memoryEnabled=false` 时直接跳过 durable fact flush
- memory 功能关闭或 flush 关闭时直接返回
- 没有可用消息时直接返回

触达的存储对象：

- 这一步本身还不写文件

失败时表现：

- 本轮不会写出 `fact`
- 聊天主流程仍继续，不因记忆失败而中断

工程落点：

- `MemoryLifecycleOrchestrator.flushDailyDurable(...)`
- `DailyDurableFlushService.flush(...)`

### 6.2 第二步：LLM 抽取结构化 facts

输入：

- 当前 projection 中的非 system 消息，按 `TYPE: content` 归一化成 transcript

输出：

- 最多 3 条结构化 `MemoryFactRecord`

决策点：

- 只保留 durable facts
- 只允许固定 bucket 枚举
- 没有 durable fact 时返回空列表

触达的存储对象：

- 还未写文件
- 只是在内存中拿到结构化 fact 列表

失败时表现：

- 抽取失败或返回空时，本轮不会写出 `fact`

工程落点：

- `MemoryLlmExtractor.extractDurableFacts(...)`

### 6.3 第三步：按 bucket 路由到目标文件

输入：

- 抽取出的 `MemoryFactRecord`

输出：

- `bucket -> facts[]` 的分组结果
- 每组对应一个目标文件路径

决策点：

- 没有 `bucket` 或没有 `fact_key` 的记录直接跳过

触达的存储对象：

- 目标文件为 `memory/users/<userId>/facts/<bucket>.md`

失败时表现：

- 某条脏 fact 被丢弃，不会参与后续 replace

工程落点：

- `DailyDurableFlushService.flush(...)`
- `MemoryFileService.factsDir(...)`

### 6.4 第四步：在 bucket 文件内做候选查找

输入：

- 一条 incoming fact
- 当前 bucket 文件解析后的已有 fact blocks

输出：

- 一个候选索引列表

决策点：

- 先按 `fact_key` 精确匹配
- 精确命中时只比较这一条
- 精确 miss 时，再做同 bucket 文件内的本地 shortlist
- shortlist 是本地启发式，不查向量库

触达的存储对象：

- 读取的是 bucket 文件本身
- 通过 `MemoryBlockParser` + `MemoryFactMarkdownCodec` 解析已有 blocks

失败时表现：

- 如果读不到旧文件，会退化为“像空文件一样处理”，后续更容易走 `ADD`

工程落点：

- `DailyDurableFlushService.loadBlocks(...)`
- `DailyDurableFlushService.resolveCandidateIndexes(...)`
- `MemoryBlockParser`

### 6.5 第五步：判定 ADD / UPDATE / NONE

输入：

- incoming fact
- 候选 fact 列表

输出：

- `MemoryFactCompareResult`

决策点：

- 无候选时默认 `ADD`
- 有候选时由第二次 LLM 决定 `ADD / UPDATE / NONE`
- 如果要求 `UPDATE`，必须返回候选中的 `match_index`

触达的存储对象：

- 仍然还没写文件

失败时表现：

- 对比异常时会偏保守，通常退化为 `ADD` 或跳过写入

工程落点：

- `MemoryLlmExtractor.compareFact(...)`

### 6.6 第六步：在 md 真源层执行追加或替换

输入：

- 对比结果
- 当前 bucket 文件的 block 列表
- incoming fact

输出：

- 更新后的 bucket markdown 文件

决策点：

- `ADD`：追加一个新的 `fact` block
- `UPDATE`：保留原 `block_id` 和原 `created_at`，刷新 `updated_at`，替换 block body
- `NONE`：不写文件

触达的存储对象：

- 真正写入的是 bucket markdown 文件

失败时表现：

- durable fact 没有落盘
- 聊天主流程仍继续，不因记忆失败而中断

工程落点：

- `DailyDurableFlushService.buildStoredBlock(...)`
- `DailyDurableFlushService.rewriteFile(...)`
- `MemoryBlockParser.renderBlock(...)`

### 6.7 第七步：watcher 感知文件变化

输入：

- bucket 文件被新增、修改或删除

输出：

- 对应 memory scope 被标记需要同步

决策点：

- 只处理相关 `.md` 事件
- 只按 scope 调度，不在 watcher 里直接做重索引

触达的存储对象：

- watcher 观察的是文件系统事件

失败时表现：

- 文件已经改了，但索引没有及时更新
- `memory_search` 可能短时间查不到新内容

工程落点：

- `MemoryFileWatchService`

### 6.8 第八步：异步 file-level reindex 更新检索

输入：

- 已被标脏的 scope
- 当前 scope 下 discover 到的 memory 文件

输出：

- 旧 chunk 被删除
- 新 chunk 被重建
- 召回侧看到最新内容

决策点：

- 仍沿用“按文件删旧 chunk 再重建”的策略
- 不做 block 级增量 patch

触达的存储对象：

- memory index 相关表
- embedding cache

失败时表现：

- 文件内容已经变了，但检索结果仍是旧内容

工程落点：

- `MemoryIndexSyncService.runSync(...)`

## 7. fact_key 与 replace 语义

### 7.1 三层身份键

当前 `fact_key` 的层级语义是：

`bucket / subject / attribute`

这表示 durable fact 的“身份”是：

- 哪个大类
- 哪个主体
- 哪个属性

而不是“这整句自然语言内容”。

### 7.2 为什么不把 value 放进 key

如果把 `value` 放进 key，同一个属性只要值变了，系统就会把它当成新事实，结果会不断追加，不会发生真正 replace。

当前设计里：

- `bucket + subject + attribute` 定义“事实槽位”
- `value + statement` 定义“槽位当前内容”

因此同一槽位值变化时，会走 `UPDATE`，而不是再长出一条新 block。

### 7.3 示例：同 fact_key 的 replace

第一次写入：

```md
<!-- MEMORY_BLOCK {"schema":"memory.v2","kind":"fact","block_id":"b1","user_id":"u1","session_id":"s1","created_at":"2026-04-07T00:00:00+08:00","updated_at":"2026-04-07T00:00:00+08:00","trigger":"preflight_compact","bucket":"user.preference","fact_key":"<same-key>"} -->
- statement: 以后都用中文回答
- subject: user
- attribute: language_and_style
- value: Chinese
<!-- /MEMORY_BLOCK -->
```

后续更新：

```md
<!-- MEMORY_BLOCK {"schema":"memory.v2","kind":"fact","block_id":"b1","user_id":"u1","session_id":"s9","created_at":"2026-04-07T00:00:00+08:00","updated_at":"2026-04-08T00:00:00+08:00","trigger":"preflight_compact","bucket":"user.preference","fact_key":"<same-key>"} -->
- statement: 以后都用中文回答，而且尽量简洁
- subject: user
- attribute: language_and_style
- value: Chinese, concise
<!-- /MEMORY_BLOCK -->
```

注意点：

- `block_id` 不变
- `fact_key` 不变
- `created_at` 保持
- `updated_at` 刷新
- 内容发生物理替换

## 8. 索引联动

durable fact replace 完成后，检索侧不是立刻同步更新，而是依赖现有异步索引链路。

### 8.1 发现范围

运行时 discovery / browse / recall / index 只认：

- `MEMORY.md`
- `facts/**/*.md`
- `summaries/**/*.md`

### 8.2 同步策略

索引仍然按文件工作：

- 文件内容 hash 变化后
- 删除旧 chunk
- 重新对整文件做 chunking
- 重新写入索引

这也是为什么 replace 固定发生在 md 真源层，而不是直接改某个向量库条目。

### 8.3 读路径关系

bucket 只用于写入更新路径，不作为 v1 读路径过滤条件。

也就是说，用户回答时仍然走现有 mixed retrieval：

- 可以召回 `fact`
- 也可以召回 `session_summary`
- 不需要先按 bucket 过滤

## 9. /new /reset、session_context 与本特性的关系

### 9.1 /new /reset

`/new`、`/reset` 不再保存 transcript archive。

当前逻辑是：

- 从当前 session projection 里取最近 15 条 projected non-system messages
- 生成一条 `session_summary`
- 追加到 `summaries/` 新文件

示例：

```md
<!-- MEMORY_BLOCK {"schema":"memory.v2","kind":"session_summary","block_id":"sum-1","user_id":"u1","session_id":"s1","created_at":"2026-04-08T00:00:00+08:00","trigger":"session_lifecycle","reason":"command:/reset","slug":"language-style-preference"} -->
- USER: 以后都用中文回答，而且尽量简洁。
- ASSISTANT: 好的，我会统一用中文并保持简洁。
- USER: 下一轮继续按这个偏好回答。
<!-- /MEMORY_BLOCK -->
```

### 9.2 session_context

`session_context_messages` 解决的是“当前有效上下文如何恢复”的问题，不是“长期记忆如何替换”的问题。

它和 durable fact replace 的关系是：

- durable fact flush 的输入来自当前 projection
- projection 本身由 `session_context` 快照负责恢复
- 但 durable fact 真源仍然是 markdown，不是 `session_context_messages`

## 10. 代码阅读入口与常见误区

### 10.1 建议阅读顺序

推荐按下面顺序读代码：

1. `MemoryLifecycleOrchestrator`
2. `DailyDurableFlushService`
3. `MemoryLlmExtractor`
4. `MemoryFactKeyFactory`
5. `MemoryBlockParser`
6. `MemoryFileWatchService`
7. `MemoryIndexSyncService`
8. `SessionArchiveService`
9. `InMemorySessionContextManager`
10. `SessionContextSnapshotStore`

### 10.2 常见误区

误区一：数据库已经是长期记忆真源。

不是。长期记忆真源仍然是 markdown。

误区二：replace 是在向量库里直接替换。

不是。replace 先发生在 md 真源层，然后再由 watcher 和异步索引消费。

误区三：`session_context` 也是 durable fact 的一种。

不是。`session_context` 是当前 projection 快照，职责是 continuity 和恢复。

误区四：bucket 也参与读路径过滤。

不是。bucket 只用于写入更新路径和物理分文件。

误区五：`fact_key` 代表整句事实。

不是。`fact_key` 代表的是三层语义键：`bucket / subject / attribute`。
