# 阶段四：`/new` 与 `/reset` Summary

## 本阶段目标

- 将 `/new`、`/reset` 的长期记忆写入从 transcript archive 改为 session summary
- summary 的输入改为当前会话上下文中的投影消息，而不是持久化全量消息原文

## Summary 规则

- 只读取当前会话上下文里的 `USER` / `ASSISTANT` 消息
- 丢弃 `SYSTEM`、thinking、tool 相关内容
- 只保留最近 15 条 non-system 消息
- 交给 LLM 生成摘要 markdown
- 输出写入 `memory/users/<userId>/summaries/YYYY-MM-DD-<slug>.md`

## Block 语义

- kind 固定为 `session_summary`
- metadata 记录：
  - `reason`
  - `slug`
  - `session_id`
  - `created_at`

## 自测点

- `/new`、`/reset` 触发后会写入 `summaries/`
- summary 输入只来自当前会话上下文，不读取持久化全量 transcript 原文
- 最近 15 条消息截断逻辑生效
