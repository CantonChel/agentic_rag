# 阶段七：新增记忆 scope 可视化页面

## 本阶段目标

- 新增统一 scope 浏览接口，向前端一次性提供 `global`、`facts`、`summaries`、`session_context`
- 新增只读页面 `memory.html`，按 scope 展示记忆内容
- 保留原有 chat 内嵌 memory 面板和 `/api/memory/file(s)` 能力不变
- 在 `chat.html` 增加跳转入口，并带上当前 `userId/sessionId`

## 新增接口

### `GET /api/memory/scopes`

- 入参：
  - `userId`
  - `includeGlobal`
- 返回：
  - `globalFiles`
  - `factFiles`
  - `summaryFiles`
  - `sessionContexts`

排序规则：

- `globalFiles`：按更新时间倒序
- `factFiles`：按 bucket 文件名排序
- `summaryFiles`：按更新时间倒序
- `sessionContexts`：按 `updatedAt` 倒序

### `GET /api/memory/session-context`

- 入参：
  - `userId`
  - `sessionId`
- 返回：
  - `sessionId`
  - `messageCount`
  - `updatedAt`
  - `messages`

访问控制语义：

- 只会在当前 `userId` 的 snapshot 列表中查找 `sessionId`
- 找不到就返回 `404`
- 不会跨用户扫描别人的 session snapshot

## 页面改动

### `memory.html`

- 左侧：
  - 用户输入框
  - 刷新按钮
  - 4 个 scope 分组
- 右侧：
  - 当前选中条目的元信息
  - markdown 文件内容，或 session context 消息序列

展示规则：

- `global/facts/summaries` 继续走旧 `/api/memory/file`
- `session_context` 走新 `/api/memory/session-context`
- `session_context` 详情以消息列表展示，不伪装成 markdown 文件

### `chat.html`

- 头部新增“记忆总览”跳转入口
- 跳转地址会带当前 `userId/sessionId`
- 页面初始化会读取 query 参数中的 `userId/sessionId`

## 兼容性说明

- 旧的 chat 内嵌记忆面板完全保留
- `GET /api/memory/files`
- `GET /api/memory/file`

以上接口字段和调用方式未改，现有前端逻辑不需要回退重做

## 本阶段自测

- `MemoryBrowseControllerTest`
- `MemoryScopeControllerTest`

覆盖点：

- 4 个 scope 分组都能返回
- `session_context` 详情接口只按当前用户查找
- 旧 memory browse 接口继续可用
- 新页面依赖的 scope 数据契约已经稳定
