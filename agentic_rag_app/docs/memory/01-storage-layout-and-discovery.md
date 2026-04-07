# 阶段一：存储布局与发现链路

## 本阶段目标

- 将用户长期记忆运行时目录切换为 `facts/` 和 `summaries/`
- 停止在 browse / get / recall / index discovery 中扫描旧 `daily/`、`sessions/`
- 将 block 语义切换为 `fact` 和 `session_summary`

## 存储布局

- 全局记忆仍为 `MEMORY.md`
- 用户 durable facts 存放在 `memory/users/<userId>/facts/*.md`
- 用户会话摘要存放在 `memory/users/<userId>/summaries/*.md`

## 运行时发现规则

- `MemoryFileService.discoverMemoryFiles(...)` 只返回：
  - `MEMORY.md`
  - `facts/**/*.md`
  - `summaries/**/*.md`
- 旧 `daily/`、`sessions/` 目录即使仍在磁盘上，也不会进入新的 memory browse / recall / index 链路
- `MemoryFileService.isAllowedForUser(...)` 同步收紧，只允许访问新的受管目录

## kind 语义

- 文件和 block 的运行时 kind 统一为：
  - `global`
  - `fact`
  - `session_summary`
- 旧 `daily_durable`、`session_archive` 退出运行时语义

## 自测点

- browse API 能看到全局文件与新的 `facts/`、`summaries/` 文件
- browse API 不再返回旧 `daily/` 文件
- `memory_get` 可以读取新 `facts/` 文件
- watcher 对 `facts/`、`summaries/` 目录变更仍会调度 user scope 索引
