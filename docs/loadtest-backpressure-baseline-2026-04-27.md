这是一份关于 Agent 负载测试背压（Backpressure）基准测试的报告。以下是中文翻译：

---

# 负载测试背压基准 (2026-04-27)

## 测试范围

本基准仅测量核心智能体场景：

*   用户发送一条消息到 `/api/agent/openai/stream`
*   智能体运行 `3` 轮（rounds）
*   前 `2` 轮每轮包含一次工具调用
*   模拟 LLM 思考延迟为每轮 `2000ms`
*   工具模式为 `fake`（模拟）
*   负载测试配置文件使用进程内模拟智能体执行器

在相同的“背压”场景下，测量了两种流式传输模式：

1. `final_answer_only`（仅最终答案）
2. `early_streaming`（早期流式传输）

报告文件：

*   `final_answer_only`: [loadtest-report-2026-04-27T05-10-56-952Z.json](/Users/luolinhao/Documents/trae_projects/agentic_rag/scripts/loadtest/reports/loadtest-report-2026-04-27T05-10-56-952Z.json)
*   `early_streaming`: [loadtest-report-2026-04-27T05-32-12-538Z.json](/Users/luolinhao/Documents/trae_projects/agentic_rag/scripts/loadtest/reports/loadtest-report-2026-04-27T05-32-12-538Z.json)

服务端快照在每次运行后通过 `GET /api/loadtest/metrics` 收集。

## 指标含义

| 指标 | 含义 |
| :--- | :--- |
| `agent_server_turn_ms` | 服务端执行一轮智能体任务的时间，从 `turn_start` 到 `turn_end`。 |
| `sse_duration_ms` | 用户端到端等待时间，从请求开始到 SSE 完成。 |
| `agent_first_token_ms` | 从请求开始到收到第一个答案 `delta` 事件的时间。 |
| `agent_first_event_ms` | 从请求开始到第一个非 `turn_start` 事件的时间。在本模拟中，通常是第一个 `thinking` 事件。 |
| `agent_queue_delay_ms` | 客户端观测到的任务实际开始前的延迟。 |
| `queueWait` | 服务端观测到的执行器队列等待时间：请求已接收但尚未执行。 |
| `firstDeltaLatency` | 服务端观测到的从执行开始到第一个答案 `delta` 的时间。 |

## 对比结果

| 指标 | `final_answer_only` | `early_streaming` | 解读 |
| :--- | ---: | ---: | :--- |
| 迭代次数 | `234` | `242` | 采用早期流式传输后，吞吐量略有提升。 |
| HTTP 失败率 | `0` | `0` | 两次运行都很稳定。 |
| SSE 错误 | `0` | `0` | 两次运行都很稳定。 |
| `agent_server_turn_ms.avg` | `8.05s` | `7.71s` | 服务执行时间相似；早期流式传输缩短了一些末尾答案延迟。 |
| `agent_server_turn_ms.p95` | `8.10s` | `7.76s` | 在尾部延迟表现上结论一致。 |
| `sse_duration_ms.avg` | `14.21s` | `13.57s` | 用户总等待时间略有改善。 |
| `sse_duration_ms.p95` | `24.15s` | `22.96s` | 尾部等待时间略有改善。 |
| `agent_first_token_ms.avg` | `14.20s` | `13.09s` | 正如预期，`early_streaming` 中的第一个答案 token 更早出现。 |
| `agent_first_token_ms.p95` | `24.13s` | `22.50s` | 尾部首个 token 延迟也有改善。 |
| `agent_first_event_ms.avg` | `8.16s` | `7.86s` | 首次可见进度略有提前。 |
| `agent_first_event_ms.p95` | `18.07s` | `17.28s` | 有小幅改善。 |
| `agent_queue_delay_ms.avg` | `6.16s` | `5.86s` | 排队仍是主要的性能惩罚项。 |
| `agent_queue_delay_ms.p95` | `16.06s` | `15.28s` | 排队仍是主要瓶颈。 |
| `queueWait.avgMs` | `6144.8ms` | `5841.3ms` | 服务端队列等待时间与客户端排队延迟高度吻合。 |
| `queueWait.maxMs` | `17771ms` | `16455ms` | 队列尾部延迟有所改善。 |
| `firstDeltaLatency.avgMs` | `8047.6ms` | `7247.3ms` | 答案的首个数据块在任务内部更早发出。 |
| `llmThinking.avgMs` | `2000ms` | `2000ms` | 模拟思考时间未变（配置设定）。 |
| `toolDuration.avgMs` | `50ms` | `50ms` | 工具调用不是瓶颈。 |
| `peakInflight` | `16` | `16` | 并发执行容量未改变。 |

## 解读

### 1. `early_streaming` 改变了什么

`early_streaming` 修正了模拟语义，使得：

*   答案 `delta` 事件在整个任务结束前就开始发出。
*   `agent_first_token_ms` 不再被强制等于 `sse_duration_ms`。

这一点在新的数据中得以体现：

*   `final_answer_only`: `agent_first_token_ms.avg = 14.20s`, `sse_duration_ms.avg = 14.21s`
*   `early_streaming`: `agent_first_token_ms.avg = 13.09s`, `sse_duration_ms.avg = 13.57s`

尽管差距仍然很小，因为该模拟仅在任务后期才开始流式传输答案，但方向正确：`agent_first_token_ms < sse_duration_ms`。

### 2. 什么没有改变

主要的瓶颈依然是**排队**，而非单轮任务的执行：

*   `agent_server_turn_ms.avg` 大约在 `7.7s` 到 `8.0s` 之间。
*   `agent_queue_delay_ms.avg` 仍然在 `6s` 左右。

这意味着：

*   单轮任务依然耗费约 `8s` 的实际执行时间。
*   在 `50` 个并发用户（VUs）下，许多请求在开始执行前还要等待约 `6s`。

因此，当前系统表现为：稳定、无请求失败，但在背压下存在明显的队列积压。

### 3. 本基准对“异步状态机重构”的参考价值

这些数据分离了两个关注点：
1. 执行成本
2. 调度/排队成本

重构工作应主要致力于改善以下指标：
*   `agent_queue_delay_ms`
*   `queueWait`
*   `sse_duration_ms`
*   `agent_first_token_ms`

且不应显著增加 `agent_server_turn_ms`。

## 当前基准结论

当前的同步长任务执行模型在当前的模拟场景下不会崩溃，但明显存在排队延迟的累积：

*   实际任务工作量约为 `8s`
*   用户可见的总等待时间约为 `13.6s` 到 `14.2s`
*   额外的 `5.8s` 到 `6.1s` 主要源于排队

这使得 `queueWait` 和 `agent_queue_delay_ms` 成为衡量下一次架构变更效果的主要前后对比指标。