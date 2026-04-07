# 阶段六：持久化 session 上下文快照

## 本阶段目标

- 为 session projection 增加独立数据库快照表 `session_context_messages`
- 保持 `PersistentMessageStore` 继续只承担 transcript 持久化，不参与 projection 重建
- 将 `InMemorySessionContextManager` 升级为“进程内热缓存 + DB 当前快照”模型
- 让 preflight compaction 改为一次性整体覆盖当前 projection

## 关键实现

- 新增 `session_context_messages` 实体与仓储
  - 字段包含 `user_id`、`session_id`、`scoped_session_id`、`message_index`、`type`、`content`、`created_at`、`updated_at`
  - 唯一约束为 `(scoped_session_id, message_index)`
  - 新增 `scoped_session_id`、`user_id` 索引
- 新增 `SessionContextSnapshotStore`
  - 支持按 `scoped_session_id` 加载当前快照
  - 支持整份快照覆盖写入
  - 支持删除快照
  - 支持按 `user_id` 枚举当前用户的 session snapshot 摘要
- `InMemorySessionContextManager`
  - 继续用 `ConcurrentHashMap` 做热缓存
  - `getContext()` cache miss 时直接从 `session_context_messages` 恢复并回填缓存
  - `ensureSystemPrompt`、`addMessage`、`replaceContext`、`clear` 都同步更新快照表
- `SessionContextPreflightCompactor`
  - 重写路径从 `clear + ensure + add` 切到 `replaceContext(...)`
  - compaction 后只做一次整体覆盖写入

## 序列化策略

- `SYSTEM` / `USER` / `ASSISTANT` / `THINKING` 直接存 `content`
- `TOOL_CALL` 将 tool call 列表序列化进 `content`
- `TOOL_RESULT` 将 `toolName`、`toolCallId`、`success`、`output`、`error` 序列化进 `content`
- 这样即使进程重启，也能从快照表恢复出可继续参与后续上下文构造的消息对象

## 风险收口

- 快照覆盖写采用“先删并 flush，再插入”的顺序，避免同一事务内撞上 `(scoped_session_id, message_index)` 唯一键
- Spring bean 保持三参数构造函数注入，额外保留两参数构造仅用于旧测试和轻量实例化兼容

## 本阶段自测

- `InMemorySessionContextManagerTest`
- `InMemorySessionContextManagerOverflowTest`
- `SessionContextPreflightCompactorTest`
- `SessionContextSnapshotStoreTest`

覆盖点：

- cache miss 从 `session_context_messages` 恢复 projection
- system prompt 替换后快照同步更新
- compaction 时只发生一次整体快照替换
- H2 下快照覆盖写、恢复、枚举、删除都正常
