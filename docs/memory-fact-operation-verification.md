# 记忆事实修改流程联调与回归验证记录

## 1. 验证时间

- 日期：2026-04-07
- 联调服务端口：`8082`
- 真实演示用户：`memory-op-demo`
- 真实演示会话：`memory-op-demo-1`

## 2. 回归测试

执行命令：

```bash
export JAVA_HOME=$(/usr/libexec/java_home) && ./mvnw -q \
  -Dtest=DailyDurableFlushServiceTest,MemoryBrowseControllerTest,MemoryScopeControllerTest,MemoryFactOperationControllerTest,SessionContextPreflightCompactorTest,MemoryGetToolTest,MemorySearchToolRegistrationTest,MemoryPromptContributorTest,MemoryIndexRepositoryTest,MemoryIndexSyncServiceTest \
  test
```

结果：

- 通过
- 覆盖了 durable flush、scope/file 浏览、fact operation API、preflight compact、memory_get、memory_search 注册、memory prompt、memory index

## 3. 真实聊天链路

### 3.1 第一轮与第二轮

过程：

1. 第一轮发送长上下文，明确注入 3 条长期事实：
   - 默认用中文回答
   - 对外说明文档放仓库根目录 `docs`
   - 当前最高优先级是“记忆事实修改流程可视化与审计”
2. 第二轮发送短消息，触发 preflight compact 与 durable flush

结果：

- 生成 `flushId = d774f595-036b-46d6-9510-9c056af6fd99`
- 写入了以下真源文件：
  - `memory/users/memory-op-demo/facts/user.preference.md`
  - `memory/users/memory-op-demo/facts/project.policy.md`
  - `memory/users/memory-op-demo/facts/project.reminder.md`
- 三条审计记录均为：
  - `decision = ADD`
  - `decisionSource = DIRECT_ADD_NO_CANDIDATES`
  - `writeOutcome = APPLIED`

## 3.2 第三轮与第四轮

过程：

1. 第三轮再次发送长上下文，补充新的项目规范：
   - 对外说明文档仍放根目录 `docs`
   - 应用内部实现说明允许放在 `agentic_rag_app/docs`
   - 同时再次确认默认中文回答
2. 第四轮发送短消息，再次触发 preflight compact 与 durable flush

结果：

- 生成了新的 compare 路径审计记录，示例 `flushId`：
  - `232dc617-698e-47ce-88b4-8fb85b727f46`
  - `b5e456e6-89fc-47b9-8f23-a5f3e547ddfa`
- `/api/memory/fact-operations` 中已能看到：
  - `decisionSource = LLM_COMPARE`
  - `candidateCount > 0`
  - 部分记录带有 `matchedBlockId`
  - `candidateFacts` 中包含旧事实

本次真实运行中的实际判定结果：

- 新增的项目规范与重复的中文偏好，在真实 LLM 裁决下都被记成了 `ADD`
- 没有在这次真实联调里观察到 `UPDATE` 或 `NONE`

这说明：

- 审计与可视化链路已经完整工作
- 真正的替换命中率仍然受事实提取的一致性和 compare prompt 裁决影响
- 如果后续需要更稳定地在真实会话里得到 `UPDATE / NONE`，应继续增强 subject / attribute 稳定性或 compare 提示词

## 4. API 与前端核验

已核验接口：

- `GET /api/memory/scopes?userId=memory-op-demo&includeGlobal=true`
- `GET /api/memory/file?userId=memory-op-demo&id=memory/users/memory-op-demo/facts/project.policy.md`
- `GET /api/memory/fact-operations?userId=memory-op-demo&path=memory/users/memory-op-demo/facts/project.policy.md&limit=20`
- `GET /api/memory/fact-operations?userId=memory-op-demo&path=memory/users/memory-op-demo/facts/user.preference.md&limit=20`

已核验前端页面：

- `GET /memory.html?userId=memory-op-demo`
- 页面源码中已包含：
  - `事实修改时间线`
  - `当前 md 真源`
  - `/api/memory/fact-operations`

这意味着前端页面已经加载到新的时间线展示逻辑，并且后端接口能返回真实数据。

## 5. 回归结论

- `facts / summaries / session_context / global` 浏览未受影响
- `fact` 文件真源读取正常
- 新审计记录不会进入 `memory_search / memory_get` 检索结果
- durable fact replace 审计记录、API 查询和记忆页时间线展示已联通
- 真实会话已验证“聊天 -> flush -> 写 md -> 查审计 -> 页面可见”的完整链路
