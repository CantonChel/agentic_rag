# 阶段五：清理、回归与联调

## 本阶段目标

- 清理测试和索引仓储中的旧 `daily/session_archive` 语义
- 补齐 memory browse / search / get / session 切换相关回归
- 做一次前后端接口层 smoke 验证，确认原有能力未被破坏

## 清理内容

- 测试样例路径统一切到 `facts/`、`summaries/`
- 测试样例 kind 统一切到 `fact`、`session_summary`
- 保留一条旧 `daily/` 负向样例，验证运行时不会再发现旧路径

## 回归重点

- memory browse API
- memory search / get
- durable fact replace
- session summary 写入
- session delete / `/new`
- preflight compaction 与异步索引

## 联调结论要求

- `chat.html` 依赖的 memory browse / file 接口字段不变
- 新记忆目录与 kind 能被前端 memory 面板继续消费
- 原有聊天主流程、会话切换、memory 工具调用不受破坏
