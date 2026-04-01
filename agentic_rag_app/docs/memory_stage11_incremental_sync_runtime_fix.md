# Memory Stage 11：增量索引修复与联调验证

## 本阶段目标

修复 PG memory 持久化索引在真实运行时暴露出的两类问题：

1. `daily/session` 文件更新后，watcher 触发的增量 sync 会因为重复 chunk 写入失败，导致新记忆只落到 markdown，不进入 `memory_index_chunks`
2. `memory_search` 的词法 SQL 在 PostgreSQL 上使用了错误的 `ESCAPE` 字符串，运行时会出现 `invalid escape string`

本阶段要求不仅修复代码，还要通过 `anonymous` 的真实多轮对话完成后端联调验证。

## 运行时问题与根因

### 1. 增量 sync 重复键失败

联调日志中反复出现：

- `uk_memory_index_chunks_scope_path_block_lines_hash`
- `Failed to sync scope user:anonymous`

根因是同一事务中执行了：

- 先删除旧 chunk
- 再写入同路径的新 chunk

但删除没有在新写入前及时 flush 到数据库。对于“旧 block 保持不变、文件尾部只追加新 block”的场景，首个 chunk 的唯一键不变，于是 PostgreSQL 先看到插入，再看到删除，触发唯一键冲突。

### 2. 词法检索 ESCAPE 写法错误

`MemoryIndexSearchRepository` 里把 SQL 写成了 `escape '\\\\'` 对应的两字符 escape 字符串，PostgreSQL 会报：

- `ERROR: invalid escape string`

正确语义应该是单字符反斜杠 `escape '\\'`。

## 本阶段修复

### 1. 调整增量 sync 的删除落库顺序

文件：

- `src/main/java/com/agenticrag/app/memory/index/MemoryIndexSyncService.java`

修改点：

- `fullReindex` 时，在删除 scope 下旧 chunk/file 后立即 `flush`
- 单文件增量更新时，在 `deleteByScopeTypeAndScopeIdAndPath(...)` 后立即 `flush`

效果：

- 确保旧 chunk 先从数据库中真正删除
- 再插入同唯一键的新 chunk 时不再触发冲突
- watcher 驱动的 append-block 场景可以稳定进入 `memory_index_chunks`

### 2. 修正 memory 词法 SQL 的 ESCAPE

文件：

- `src/main/java/com/agenticrag/app/memory/index/MemoryIndexSearchRepository.java`

修改点：

- 将 `escape '\\\\'` 改为 PostgreSQL 可接受的单字符 `escape '\\'`

效果：

- `memory_search` 的词法分支不再抛 `invalid escape string`
- 向量与词法混合检索都可以正常参与 recall

## 回归测试

### 单元/集成测试

新增/更新：

- `src/test/java/com/agenticrag/app/memory/MemoryIndexSyncServiceTest.java`
  - 新增 `rerunsSyncForAppendedMemoryBlockWithoutDuplicateChunkFailure`
  - 用真实 `MEMORY_BLOCK` 追加场景复现“首块不变、尾部新增”的增量 sync
  - 验证不会再因为重复 chunk 唯一键报错
- `src/test/java/com/agenticrag/app/memory/MemoryIndexSearchRepositoryTest.java`
  - 验证词法 SQL 生成的是单字符反斜杠 escape
  - 同时检查 `%` / `_` 的 like 参数仍会被正确转义

执行命令：

```bash
cd agentic_rag_app
./mvnw clean -Dtest=MemoryIndexSyncServiceTest,MemoryIndexSearchRepositoryTest,MemorySearchServiceTest,MemoryRecallServiceTest test
```

结果：

- `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`

## 前后端联调验证

### 启动方式

必须使用重启脚本，不直接手工起 Java 进程：

```bash
CONTEXT_SESSION_MAX_TOKENS=320 \
CONTEXT_SESSION_PREFLIGHT_RESERVE_TOKENS=80 \
MEMORY_ENABLED=true \
MEMORY_FLUSH_ENABLED=true \
MEMORY_INDEX_STARTUP_SYNC_ENABLED=true \
MEMORY_WATCHER_ENABLED=true \
./scripts/restart_all.sh
```

### 联调脚本

使用 `anonymous` 做真实多轮写入，再开新会话 recall：

1. 写入唯一测试原则
   - `项目代号：苍穹-77`
   - `试点范围：华北一区`
   - `不开放西南区`
2. 再追问一轮，触发 `daily durable flush`
3. 轮询 PG：
   - `memory_index_meta(scope=user:anonymous)` 必须恢复为 `dirty=false`
   - `memory_index_chunks` 必须查到包含 `苍穹-77` 的新 chunk
4. 新建第二个 `anonymous` 会话
5. 直接问 recall 问题，验证 `memory_search -> memory_get`

### 联调结果

结果产物目录：

- `.logs/anonymous_memory_probe_fix/summary.json`

关键结果：

- `daily` 文件从 `9229` 增长到 `9726`
- `dailyContainsUniqueTag = true`
- `memory_index_meta(user:anonymous).dirty = false`
- `memory_index_chunks` 已查到：
  - `项目代号：苍穹-77`
  - `试点范围：华北一区`
  - `区域限制：不开放西南区`
- `recallHitNewDaily = true`
- `recallAnswerContainsUniqueTag = true`

也就是说，本阶段已经验证：

- markdown 落盘成功
- watcher 增量 sync 成功
- PG chunk 建索引成功
- 新会话 recall 成功

## 结论

本阶段修复后，memory 增量索引链路已经从“只写文件，不稳定入索引”变成了：

`daily/session markdown 更新 -> watcher 标脏 -> PG 增量 sync -> memory_index_chunks 可查 -> 新会话 recall`

这次联调直接覆盖了之前失败的真实场景，说明问题已经被修正，而不是只在单元测试里被绕开。
