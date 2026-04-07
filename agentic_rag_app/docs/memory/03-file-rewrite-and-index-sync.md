# 阶段三：文件重写与索引同步

## 本阶段目标

- 将 durable fact 的物理更新固定在 md 真源层完成
- 确认现有 watcher + user scope 异步索引链路可以正确消费新的 `facts/`、`summaries/`

## 文件重写策略

- bucket 文件先完整读取
- 在内存里完成 block 级替换
- 先写同目录临时文件
- 再通过 `move` 覆盖目标文件
- 优先尝试 `ATOMIC_MOVE`，不支持时降级到普通替换

## 索引联动

- watcher 仍按 user scope 调度索引
- index sync 仍沿用现有“按文件删旧 chunk 再重建”的策略
- 新的 `facts/` 文件会进入 user scope
- 新的 `summaries/` 文件也会进入 user scope

## 自测点

- 同一个 fact 文件被重写后，旧 chunk 会被删掉，新 chunk 会被重建
- `facts/` 与 `summaries/` 文件都能被索引进入 user scope
- 同一路径重复同步不会出现重复 chunk 冲突
