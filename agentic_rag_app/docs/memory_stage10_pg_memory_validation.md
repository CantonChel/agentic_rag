# 第十阶段：记忆 PG 索引回归、验证与排障收口

## 本阶段目标

这一阶段不再扩展新的主功能，而是把 PG memory 索引链路的回归覆盖、人工验证路径和排障入口全部定稿。

关注点固定为四类：

- 索引同步是否稳定
- watcher 增量刷新是否稳定
- `memory_search -> memory_get` 契约是否稳定
- 运维排障时能否快速从 PG 表里看清状态

## 测试清单

本阶段补齐或更新了下面这些测试：

- `MemoryIndexSyncServiceTest`
  - `needsFullReindex` 任一比较字段变化会触发 full reindex
  - 文件 hash 未变化时整文件 skip，不重复 embedding
  - full reindex 时仍可复用 `embedding_cache`
  - 启动 dirty 后旧 chunk 仍可读
  - 删除 markdown 文件后，旧 `files/chunks` 会在下一轮 sync 中消失
  - `sessions` 目录下的归档 markdown 会进入可检索集合
- `MemoryIndexManagerTest`
  - 同一 scope 的重复请求不会并发执行 sync
  - sync 失败后会重新把 scope 标脏
- `MemoryFileWatchServiceTest`
  - `daily` / `sessions` / `MEMORY.md` 变化会调度对应 scope
  - 新目录会递归补注册
  - `OVERFLOW` 会退回全量标脏补同步
  - cache 文件和非 markdown 文件会被忽略
- `MemorySearchServiceTest`
  - 搜索范围固定为 `global + currentUser`
  - 向量候选与词法候选会稳定融合去重
  - dirty / missing scope 会触发后台补同步
  - embedding 不可用时会退化为词法召回
- `MemoryRecallServiceTest`
  - `search()` 会委托到 PG 持久化检索服务
  - `get()` 只按路径和行号回源读取 markdown 原文
- `MemoryGetToolTest`
  - 能按 `path + lineStart + lineEnd` 返回原文
  - 非法范围会在调用 recall 前直接拒绝
- `MemoryPromptContributorTest`
  - 同时可见 `memory_search + memory_get` 时会约束模型先搜再读
  - 仅有 `memory_get` 时也会保留精读原文的提示

## 人工验证步骤

### 1. 冷启动构建索引

- 清空目标 user 的 `memory/users/<userId>/daily` 和 `sessions`
- 启动应用
- 写入一份新的 daily markdown
- 观察 `memory_index_meta.dirty` 从 `true` 变成 `false`
- 观察 `memory_index_files / memory_index_chunks / memory_index_embedding_cache` 出现对应记录

### 2. 重启后旧索引先可用

- 确保 PG 里已有上一轮索引数据
- 重启应用
- 在后台 sync 完成前直接调用 `memory_search`
- 确认仍能命中旧 chunk
- 再确认对应 scope 的 `dirty` 最终回落为 `false`

### 3. 修改 daily 文件后的增量刷新

- 更新 `memory/users/<userId>/daily/*.md`
- 等待 watcher 调度
- 确认 `memory_index_files.content_hash` 更新
- 确认相关 `memory_index_chunks` 内容被替换
- 再用 `memory_search` 验证新内容可命中

### 4. 生成 session archive 后进入可检索集合

- 在 `memory/users/<userId>/sessions/` 下新增归档 markdown
- 等待 watcher 或手动触发 sync
- 确认 `memory_index_chunks.kind = session_archive`
- 再用 `memory_search` 验证该会话归档可以被召回

### 5. 删除文件后的命中消失

- 删除已索引的 daily 或 session markdown
- 等待 sync 完成
- 确认对应 `memory_index_files` 与 `memory_index_chunks` 被清理
- 再次检索，原命中应消失

### 6. 验证 `memory_search -> memory_get`

- 先执行 `memory_search`
- 记录返回的 `path / lineStart / lineEnd`
- 再执行 `memory_get`
- 确认拿回的是 markdown 原文，不是索引里的二次摘要

## 典型排障 SQL 与排障思路

### 看 scope 是否还脏

```sql
select scope_type, scope_id, dirty, last_sync_at, last_error
from memory_index_meta
order by scope_type, scope_id;
```

排障思路：

- `dirty=true` 长时间不回落，优先看 manager / watcher / sync 日志
- `last_error` 不为空，先按错误信息定位 embedding、文件读取或 SQL 问题

### 看文件 hash 是否更新

```sql
select scope_type, scope_id, path, kind, content_hash, file_mtime, indexed_at
from memory_index_files
order by indexed_at desc;
```

排障思路：

- markdown 明明改了但 `content_hash` 不变，先确认 watcher 是否真的观察到目标文件
- 文件已删除但记录还在，说明删除事件没触发或 sync 未补跑

### 看 chunk 是否落进去

```sql
select scope_type, scope_id, path, kind, block_id, line_start, line_end, left(content, 120) as snippet
from memory_index_chunks
order by scope_type, scope_id, path, line_start;
```

排障思路：

- daily/session 文件已存在但没有 chunk，先看 chunking 是否把内容切出来
- `kind` 不对时，优先检查文件路径是否落在 `daily/` 或 `sessions/`

### 看 embedding cache 是否复用

```sql
select provider, model, provider_key_fingerprint, chunk_hash, dimension, updated_at
from memory_index_embedding_cache
order by updated_at desc;
```

排障思路：

- full reindex 后 cache 记录数量不稳定增长，说明 chunk hash 复用失败
- `dimension` 异常变化时，优先检查 embedding provider/model 是否切换

## 已知限制与后续优化项

- 当前词法侧仍是 PG 轻量 `ILIKE + tsvector + similarity`，没有升级到 BM25/Lucene
- 当前测试以 mock 和现有 SpringBoot 测试为主，还没有引入 Testcontainers 做 pgvector 真实集成验证
- `memory_get` 继续回源读取 markdown，所以如果底层文件被人工改坏但索引尚未刷新，短窗口内可能出现“候选来自旧索引、原文来自新文件”的瞬时不一致
- watcher 依赖本地文件系统事件；极端情况下若底层大量抖动导致 `OVERFLOW`，系统会退回全量 scope 标脏补跑，而不是做更细的事件重放

## 本阶段结果

到这一阶段为止，PG memory 索引链路已经具备：

- 可持久化的 meta/files/chunks/embedding_cache 真相源
- 启动 dirty、旧索引先可用、后台异步增量 sync
- watcher 驱动的 scope 补同步
- `memory_search` 的 PG 持久化混合检索
- `memory_get` 的原文精读闭环
- 覆盖主要边界的回归测试与运维排障入口
