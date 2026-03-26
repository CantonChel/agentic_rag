# 聊天会话精确回放说明

## 目标

本次改造主要解决 4 个体验问题：

1. 刷新页面时，不再默认新建会话，而是自动回到当前用户最近活跃的会话
2. 左侧点击“新建会话”后，当前聊天区立即清空
3. 左侧切换会话时，前端自动加载历史
4. 刷新后历史不再碎成多个 assistant 小卡片，而是恢复为在线时的一个 assistant 大卡片

## 核心方案

### 1. 会话选择

- `GET /api/sessions` 返回 `sessionId / createdAt / lastActiveAt / hasMessages`
- 返回顺序按 `lastActiveAt` 倒序
- 页面初始化优先加载第一个会话
- 只有当用户没有任何会话时，前端才创建新会话

### 2. 精确回放存储

保留原有 `stored_messages`，继续承担上下文和调试职责。

新增 `session_replay_events`，专门用于前端历史重建，记录 assistant 侧流式事件：

- `turn_start`
- `thinking`
- `delta`
- `tool_start`
- `tool_end`
- `done`
- `turn_end`
- `error`

关键字段包括：

- `turnId`
- `sequenceId`
- `roundId`
- `toolCallId`
- `toolName`
- `status`
- `durationMs`
- `argsPreviewJson`
- `resultPreviewJson`
- `eventTs`

### 3. 回放接口

新增接口：

- `GET /api/sessions/{sessionId}/replay?userId=...`

返回统一时间线：

- `kind = user_message`
- `kind = assistant_event`

前端按服务端返回顺序直接回放，不再自己重排事件。

### 4. 前端恢复策略

历史恢复时优先走 replay：

- `user_message`：追加一条 user 卡片
- `assistant_event.turn_start`：新建 assistant 大卡片
- 同一 `turnId` 的 `thinking/tool/content/done/turn_end`：全部进入同一张 assistant 卡片

如果旧会话没有 replay 数据，则回退到 `/messages`：

- 按 user 分段
- 同一段内的 `ASSISTANT / THINKING / TOOL_CALL / TOOL_RESULT` 尽量归并到一张 assistant 卡片

## 代码位置

- 会话摘要与排序：
  - `agentic_rag_app/src/main/java/com/agenticrag/app/session/SessionSummaryService.java`
- replay 存储：
  - `agentic_rag_app/src/main/java/com/agenticrag/app/chat/store/SessionReplayStore.java`
- replay 接口：
  - `agentic_rag_app/src/main/java/com/agenticrag/app/api/SessionController.java`
- 前端回放恢复：
  - `agentic_rag_app/src/main/resources/static/chat.html`

## 联调结果

已使用真实 `MiniMax Agent` 链路联调，验证到以下结果：

1. 能收到真实 `thinking` 事件
2. 能收到真实 `tool_start / tool_end` 事件
3. 页面刷新后会自动回到最近活跃会话
4. 刷新后同一轮对话仍恢复成 1 张 assistant 大卡片
5. 左侧新建会话后聊天区立即清空
6. 左侧切回旧会话后，历史会自动恢复

## 当前限制

1. 旧会话如果没有 replay 数据，只能走兼容回退，效果是“尽量不碎”，不保证完全等同在线时序
2. replay 只精确恢复 assistant 侧事件，user 消息仍来自 `stored_messages`
