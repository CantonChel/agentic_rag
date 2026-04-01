# 第八阶段：记忆 watcher 驱动的 scope 刷新

## 本阶段目标

这一阶段把 memory 的“文件变化 -> 标脏 -> 补同步”链路补齐。

watcher 的职责被明确限制为两件事：

- 监听 memory markdown 文件变化
- 按 scope 调用 `MemoryIndexManager` 去标脏并排队 sync

它不直接参与检索，也不直接写 chunk。

## watcher 生命周期

本阶段新增 `MemoryFileWatchService`：

- 启动：`@PostConstruct`
- 运行模型：`WatchService + 单线程 daemon executor`
- 停止：`@PreDestroy`

启动时会做三步初始化：

1. 注册 workspace root，用来观察 `MEMORY.md` 与 `memory/` 根目录变化
2. 注册 `memory/` 根目录，用来观察 `memory/users` 的创建
3. 若 `memory/users` 已存在，则递归注册其下所有子目录

这样既能覆盖已有目录，也能覆盖运行中新增的 user 目录。

## 监听范围

逻辑监听范围固定为：

- `MEMORY.md`
- `memory/users/**/daily/**`
- `memory/users/**/sessions/**`
- `memory/users/<userId>/*.md` 这类 legacy markdown

显式忽略：

- `memory/.cache/**`
- 非 markdown 文件

实现上不是去 watch `memory/.cache`，而是在事件处理阶段先做 path 过滤，确保 cache 目录变化不会进入 sync 调度。

## scope 定位规则

`MemoryIndexScopeService.resolveScopeForPath()` 负责把文件路径映射成 scope：

- 路径等于 `MEMORY.md`
  - 映射为 `global`
- 路径位于 `memory/users/<userId>/...`
  - 映射为 `user:<userId>`

因此 watcher 不需要自己解析路径规则，只需要把事件路径交给 scope service。

## 事件语义

本阶段处理四类事件：

- `ENTRY_CREATE`
  - 新 markdown 文件：调度对应 scope
  - 新目录：递归补注册，并顺手调度一次对应 scope
- `ENTRY_MODIFY`
  - markdown 内容更新：调度对应 scope
- `ENTRY_DELETE`
  - markdown 删除或目录删除：调度对应 scope，让 sync 清理旧 chunk
- `OVERFLOW`
  - 触发 `markAllKnownScopesDirtyAndRequestSync()`
  - 不允许静默丢失变化

## 去抖与补跑策略

watcher 引入了固定的 `300ms` 去抖窗口：

- 同一个 scope 在 300ms 内重复收到事件时，只保留第一次调度
- 调度本身仍走 `MemoryIndexManager.requestSync()`

由于 manager 已经有：

- `pending`
- `running`

两层控制，所以即使去抖窗口内被压缩掉了多个事件，只要第一次已经把 scope 送进队列，后续 sync 仍会按 scope 串行补跑。

## 失败恢复策略

watcher 自身只负责观察与调度，不承担恢复写入。

失败恢复分两层：

- watcher 线程异常
  - 捕获并记录日志，不让线程直接把进程拖死
- sync 失败
  - manager 在事务外重新把 scope 标脏，并写 `last_error`
  - 后续 watcher 新事件或显式调度都能再次补跑

`OVERFLOW` 的策略也属于恢复机制的一部分：一旦底层事件队列不可信，就退回到“全量 scope 标脏并重跑”的保守路径。

## 本阶段结果

这一阶段完成后：

- 修改 `daily` / `sessions` / legacy markdown 会自动驱动对应 scope 进入 sync
- 删除文件后，对应旧 chunk 会在下一轮同步里消失
- 新目录会被递归补注册
- `OVERFLOW` 不会静默丢变化，而是退回全量补同步
