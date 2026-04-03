# 实现一个具有分步思考能力的 Agent SOP

## 1. 文档目标与阅读方式

这份 SOP 用于复盘 `agentic_rag_app` 当前是如何把大模型、工具调用和多轮执行循环组织成一个“具有分步思考能力的 Agent”的。

它重点回答以下问题：

1. 本项目的 Agent 为什么不是一次性生成答案，而是一个多轮执行循环
2. OpenAI-Compatible 流式响应里，普通内容、思考内容和工具调用分别长什么样
3. 工具是如何定义、注册、注入给大模型，并最终形成 `assistant(tool_calls) -> tool(result) -> assistant(next round)` 闭环的
4. `todo_write` 和 `thinking` 在这个项目里到底承担什么职责，它们和模型原生 reasoning 有什么区别
5. 当前本项目的工作流提示词和你给出的 Go / WeKnora 风格工作流相比，强弱分别在哪里
6. 当前实现有哪些 guardrail，哪些地方还容易出现“过程幻觉”

这篇文档采用“现状 + 对比 + 目标”的写法：

- 先准确说明当前分支代码已经实现了什么
- 再单独对比 Go 项目 Prompt 的工作流设计
- 最后给出推荐的目标方案

建议按下面顺序阅读：

1. 先看“Agent 整体执行架构”
2. 再看“OpenAI SDK 流式字段”和“三路分流”
3. 然后看 “Tool Router”、“ReAct Execute Loop”
4. 最后看 `todo_write` / `thinking`、Prompt 对比和推荐目标方案

---

## 2. Agent 整体执行架构

当前项目里的 Agent 不是一个“单轮问答器”，而是一个“流式模型调用 + 工具执行 + 结果回填 + 下一轮继续推理”的执行循环。

主链路可以先概括成：

```text
User Query
 -> System Prompt
 -> LLM Streaming
 -> content / reasoning / tool_calls 分流
 -> Tool Execute
 -> Tool Result 回填
 -> 下一轮 LLM
 -> 无工具调用后结束
```

最核心的几个类是：

- `AgentStreamingService`：主执行循环
- `OpenAiMessageAdapter`：本地消息和 OpenAI SDK message 互转
- `ToolRouter`：工具注册与路由
- `ThinkingTool`：显式思考工具
- `TodoWriteTool`：计划工具

当前实现的关键点是：

- 模型输出不是直接全部作为“最终答案”处理
- 工具调用也不是前端单独代执行
- Agent 会把本轮的工具调用与工具结果重新写回上下文，再进入下一轮模型调用

因此，这个项目里“分步思考能力”的真正来源，不是某个模型厂商是否暴露了 chain-of-thought，而是下面四层共同作用：

1. 系统提示词约束工作流
2. 工具协议约束 Action 形态
3. 多轮 loop 约束推理顺序
4. observation 回填约束下一步行为

---

## 3. OpenAI SDK 流式字段与经典用法

当前项目并没有手动去解析服务商原始 SSE 文本，而是通过 OpenAI Java SDK 直接读取流式 `ChatCompletionChunk`。

在 `AgentStreamingService` 里，模型调用的典型形状是：

```text
ChatCompletionCreateParams
 -> createStreaming(...)
 -> StreamResponse<ChatCompletionChunk>
 -> for chunk in stream
```

### 3.1 当前最重要的字段

当前代码里最关键的字段是：

- `chunk.choices()`
- `choice.delta()`
- `delta.content()`
- `delta.toolCalls()`
- `choice.finishReason()`
- `chunk.model()`
- `delta._additionalProperties()`

可以把它们粗略理解成：

```text
delta.content           -> 普通文本 token
delta.toolCalls         -> function/tool call 片段
choice.finishReason     -> 这一轮为什么结束
chunk.model             -> 实际返回的模型名
_additionalProperties   -> OpenAI-compatible 厂商扩展字段
```

### 3.2 当前项目里的经典读取姿势

当前实现依赖的是最常见、也最实用的读取方式：

```java
try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
    for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) stream.stream()::iterator) {
        for (ChatCompletionChunk.Choice choice : chunk.choices()) {
            ChatCompletionChunk.Choice.Delta delta = choice.delta();

            delta.content().ifPresent(...);
            delta.toolCalls().ifPresent(...);

            if (choice.finishReason().isPresent()) {
                ...
            }
        }
    }
}
```

也就是说，本项目的第一层输入不是“原始 SSE 字符串”，而是 SDK 已经帮你拆好的 `ChatCompletionChunk`。

### 3.3 为什么还要看 `_additionalProperties()`

这是因为 OpenAI-Compatible 厂商对“思考内容”的输出位置并不统一。

当前项目已经适配了 MiniMax 的 reasoning 扩展字段。它会从 `_additionalProperties()` 中尝试提取：

- `reasoning_details`
- `reasoning_content`
- `thinking`
- `reasoning`

这部分逻辑集中在 `MinimaxReasoningSupport`。

这意味着：

- 普通文本，不一定等于“全部可见思考”
- reasoning 内容，也不一定会出现在 `delta.content`
- 不同厂商可能把推理内容放在完全不同的字段里

所以当前项目做的不是“假设所有思考都在 content 里”，而是对 provider side-channel 做了兼容层。

### 3.4 当前项目的一个重要工程判断

当前项目虽然支持 provider reasoning 字段，但它并没有把“分步思考能力”完全押在这些字段上。

更稳定的承载方式是：

- 原生 reasoning 字段：兼容、观察、展示
- `thinking` 工具：显式、稳定、可控

这一点在后面的 `thinking` 一章会继续展开。

---

## 4. 从服务商 SSE 到本地事件流的三路分流

收到服务商返回的 `ChatCompletionChunk` 后，本项目不会把所有内容都当成一条统一文本流处理，而是拆成三条不同路径：

1. 普通内容路径
2. 思考内容路径
3. 工具调用路径

可以把它简化成：

```text
chunk.delta
├─ content        -> assistantContent / delta 事件
├─ reasoning      -> reasoningBuffer / thinking 事件
└─ tool_calls     -> ToolCallAccumulator / 一轮结束后再执行
```

### 4.1 普通内容路径

普通文本来自 `delta.content()`。

当前项目会把内容交给 `ThinkTagParser` 做一次额外处理：

- 不在 `<think>...</think>` 里的部分，视为普通回答内容
- 追加到 `assistantContent`
- 同时发出 `LlmStreamEvent.delta(...)`

这意味着：

- 普通文本不需要等整段拼完才输出
- token 到来时就可以边收边流

### 4.2 思考内容路径

当前项目里的思考内容有两种来源。

#### 第一类：正文里的 `<think>...</think>`

如果 `delta.content()` 中混入了 `<think>...</think>`，`ThinkTagParser` 会把它拆开：

- answer 部分仍走普通内容路径
- think 部分进入 `inlineThinkBuffer`
- 再发出 `thinking` 事件

#### 第二类：provider reasoning field

如果 reasoning 内容不在 `content` 里，而是在 provider 扩展字段中，当前项目会：

1. 从 `_additionalProperties()` 里抽取 reasoning 片段
2. 追加到 `reasoningBuffer`
3. 再发出 `thinking` 事件

所以，当前项目中的“思考流”与“普通文本流”并不是同一条路径。

### 4.3 工具调用路径

`delta.toolCalls()` 与前两者的处理方式不同。

这里有个关键现实约束：

- 函数名可能被拆成多个 chunk
- arguments JSON 也可能被拆成多个 chunk

所以当前项目不会在看到第一个 `tool_call` 片段时立刻执行工具，而是：

1. 按 `index` 找到对应工具调用槽位
2. 逐步累积：
   - `id`
   - `function.name`
   - `function.arguments`
3. 等本轮模型流结束后，再 build 成完整的 `LlmToolCall`

这就是 `ToolCallAccumulator` 的职责。

### 4.4 三条路径的根本区别

这一章最重要的结论是：

- 普通内容：token 级实时流出
- 思考内容：也可流式发出，但走独立 `thinking` 事件
- 工具调用：先缓冲、后构造、再执行

因此，“收到一个 SSE 事件后，马上怎么处理”，当前项目不是一个分支，而是三套不同的处理规则。

### 4.5 从 chunk 怎么回到 loop

一轮流式结束后，会有两个关键产物：

- `assistantFinal`
- `toolCalls`

分支逻辑是：

1. 如果 `toolCalls` 为空  
   说明当前轮已经形成了最终回复，本轮结束。

2. 如果 `toolCalls` 不为空  
   说明当前轮只完成了“思考 + 选工具”，还没有完成整个任务。此时需要：
   - 记录 `ToolCallMessage`
   - 执行工具
   - 记录 `ToolResultMessage`
   - 把这些 observation 回填给下一轮模型调用

这就是当前项目真正的多轮 Agent loop 入口。

---

## 5. Tool Router 与 Function Calling 闭环

### 5.1 当前工具接口长什么样

当前项目里的工具统一实现 `Tool` 接口，核心能力只有四个：

- `name()`
- `description()`
- `parametersSchema()`
- `execute(arguments, context)`

这说明工具协议本身是非常简洁的：

```text
工具名
 + 描述
 + 参数 JSON Schema
 + 执行结果
```

### 5.2 工具怎么注册

当前项目通过 `ToolAutoRegistrar` 自动把所有 `Tool` Bean 注册到 `ToolRouter`。

因此，工具注册不是手工硬编码列表，而是 Spring 容器驱动的自动收集。

### 5.3 工具如何注入给大模型

工具定义会进入两条链路。

#### 第一条：进入 OpenAI function tools

`ToolDefinition` 会被转换成 OpenAI SDK 所需的：

- `FunctionDefinition`
- `FunctionParameters`
- `ChatCompletionTool`

然后传给模型。

#### 第二条：进入系统提示词

`RegisteredToolsPromptContributor` 会把当前允许使用的工具列表拼进系统提示词。

这意味着模型不只是“API 层知道能调什么工具”，也会在 prompt 中看到：

- 工具名
- 描述
- 参数 schema

因此，当前项目对工具使用的约束其实是“双重注入”：

```text
API 层 function tool 列表
+ Prompt 层工具契约说明
```

### 5.4 Function Calling 的消息闭环

这是本项目最关键的一条链路：

```text
assistant(tool_calls)
 -> tool(result)
 -> assistant(next round)
```

当前项目通过 `OpenAiMessageAdapter` 把本地消息重新映射成 OpenAI SDK 所需的 message：

- `UserMessage` -> user message
- `AssistantMessage` -> assistant message
- `ToolCallMessage` -> assistant with `tool_calls`
- `ToolResultMessage` -> tool message with `tool_call_id`

这意味着：

- 工具调用不是只展示给前端看
- 工具结果也不是只记录日志
- 它们会真实回到下一轮模型上下文里

所以，本项目是一个真正的 function calling 闭环，而不是“前端把工具结果拼一拼再发给用户”的假 Agent。

---

## 6. ReAct Execute Loop 与错误容错

当前项目的 Agent 主循环，本质上就是一个 ReAct 风格的执行循环：

```text
Thought / Reflect
 -> Action
 -> Observation
 -> Next Thought
```

### 6.1 当前 loop 的基本结构

循环的大致过程是：

1. 组装本轮 system prompt 和上下文
2. 调模型流式输出
3. 拆 content / reasoning / tool_calls
4. 如果没有工具调用，则结束
5. 如果有工具调用，则执行工具
6. 把工具结果作为 observation 写回上下文
7. 进入下一轮

这条循环在 `AgentStreamingService` 里通过：

```text
while (!finished && iteration < maxIterations)
```

完成。

### 6.2 当前为什么可以叫 ReAct

当前项目虽然没有在代码里写出 “ReAct” 这个词，但它已经具备了完整的形态：

- Reflect：`thinking` 工具或 reasoning 缓冲
- Action：tool call
- Observation：tool result
- Loop：多轮执行直到不再需要工具

### 6.3 错误为什么不直接报错退出

这是当前项目里非常重要的一个设计点。

对工具错误，当前处理思路不是：

- 立刻中断整个 Agent
- 把错误只抛给用户

而是尽量把错误转换成 observation，交还给模型自己修正下一步。

具体包括：

- 参数 schema 校验失败
- 工具不存在
- 工具超时
- 工具抛异常
- 工具返回 null

这些情况都会尽量转成：

```text
ToolResult.error(...)
 -> ToolResultMessage
 -> 下一轮模型上下文
```

因此，当前项目的错误容错哲学是：

```text
错误不是立即终止
而是优先转成 observation 回填给模型
```

这也是 ReAct Agent 比“一次性大模型问答”更稳的地方之一。

### 6.4 当前 loop 的硬边界

虽然工具错误尽量不直接中断，但 loop 仍然有几个明确的硬边界：

- `maxIterations`
- context window 接近上限时的 finalization
- final iteration warning
- client cancellation
- provider / SDK 异常

其中两个典型保护是：

1. 最后一轮强制收束  
   如果已经接近最大思考步数，系统 prompt 会追加警告，要求停止调新工具、直接收尾。

2. 上下文溢出保护  
   如果上下文窗口逼近极限，会进入 fallback finalization，而不是继续盲目调用新工具。

### 6.5 当前 loop 的局限

当前 loop 虽然已经是一个有效的 ReAct 骨架，但它还没有把工作流升级成“强状态机”。

例如：

- 不检查 `todo` 是否真的全部完成
- 不检查某次 retrieval 后是否一定调用了 `thinking`
- 没有独立 `final_answer` 工具作为最终收束协议

这也是后文要和 Go 项目工作流对比的重点。

---

## 7. `todo_write`：计划工具、schema 与软状态边界

### 7.1 当前 Java 版 schema 很轻量

当前 Java 版 `todo_write` 的输入非常简单：

- `task`
- `steps[]`

每个 `step` 只有：

- `id`
- `description`
- `status`

它的本质是：

```text
一个复杂任务的总目标
+ 一组可跟踪的研究/检索步骤
```

### 7.2 `task` 到底是不是用户 query

从当前工具定义看，`task` 的语义是：

```text
The complex task or question you need to create a plan for.
```

所以更准确地说：

- `task` 通常来源于用户 query
- 但它不一定逐字等于用户原始问题
- 它更像“当前复杂任务的总目标”或“计划标题”

也就是说，`task` 常常是“用户 query 的任务化表述”。

### 7.3 当前 Java 版 `todo_write` 实际做了什么

当前 Java 版 `todo_write` 并不会维护一个真正的后端任务状态机。

它的执行更接近：

1. 接收模型填写好的 `task + steps`
2. 把它格式化成一段计划文本
3. 把这段文本作为工具输出返回

所以当前 Java 版更像：

```text
计划快照工具
```

而不是：

```text
后端权威任务管理器
```

### 7.4 为什么它是“轻量软状态”

当前 Java 版之所以是轻量软状态，是因为它缺少这些能力：

- 没有持久化的 plan store
- 没有 `todo_read`
- 没有 `todo_update`
- 没有状态迁移校验
- 没有“只允许一个 in_progress”的服务端约束
- 没有“所有任务完成前禁止综合”的 runtime gating

所以当前 `todo_write` 的状态语义，主要还是靠：

- prompt 约束
- 大模型自律
- loop 中的上下文回填

这就是“软状态”的典型表现。

### 7.5 Go 版 `todo_write` 为什么更强

你给的 Go 版 `todo_write` 比当前 Java 版明显更强，强在：

- 工具描述更完整
- task state 约束更清晰
- 强调只允许一个 `in_progress`
- 强调所有任务完成后才允许综合
- 返回了更丰富的结构化 `Data`
- 输出文本里会显示当前任务统计和未完成提醒

因此，它不是“轻量软状态”，而是：

```text
强化软状态
```

### 7.6 为什么 Go 版仍不是硬状态机

即使如此，Go 版 `todo_write` 仍然不等于硬状态机。

原因是它依然没有体现这些能力：

- 服务端持有权威 plan state
- 独立读取当前 plan 的能力
- 由服务端决定哪些状态变化合法
- 自动阻止“未完成任务就直接综合”
- 将工具执行结果自动绑定到任务状态

因此，Go 版更准确地说是：

```text
snapshot upsert + echo
```

也就是：

- 模型提交当前计划快照
- 工具回显当前计划快照
- 下一轮模型再基于这个快照继续推进

这比当前 Java 版强，但依然还是模型主导的计划语义。

---

## 8. `thinking`：显式思考工具与模型原生 reasoning 的区别

这是当前项目里非常重要的一层设计。

### 8.1 当前项目里有两类“思考”

第一类是模型原生 reasoning：

- 来自 provider 扩展字段
- 或来自正文里的 `<think>...</think>`

第二类是显式 `thinking` 工具：

- 由 Agent 主动调用
- 由工具协议承载
- 输出进入 tool result

### 8.2 原生 reasoning 是什么

原生 reasoning 更像 side-channel。

它的特点是：

- 位置不稳定
- 厂商不统一
- 有的模型有，有的模型没有
- 更适合“展示、兼容、观察”

当前项目对 MiniMax reasoning 做了适配，但并没有把它当成唯一思考来源。

### 8.3 `thinking` 工具是什么

`thinking` 工具的参数也很简单，核心是：

- `thought`
- `total_thoughts`
- `is_revision`
- `round`

执行时，它本质上只是把 `thought` 回传。

但它的真正意义不在计算本身，而在协议：

- 它是显式 action
- 它的输出是明确 observation
- 它能够进入 Agent loop

### 8.4 为什么更稳定的思考应该绑定到工具协议

这是当前项目的一个重要工程判断：

- 如果只依赖模型原生 reasoning 字段，你就被厂商实现绑定了
- 如果思考通过 `thinking` 工具显式表达，思考协议就变成你自己控制的系统行为

因此，更稳定的分步思考能力，应该绑定到：

```text
thinking tool
+ loop
+ observation 回填
```

而不是只绑定到：

```text
provider 私有 reasoning 字段
```

### 8.5 `ThinkingMessage` 为什么不等于下一轮 observation

当前项目这里有一个非常细的设计点。

`ThinkingMessage` 会被记录下来，用于：

- 持久化
- 回放
- 可见性展示

但它不会直接进入下一轮模型上下文，因为上下文压缩时会过滤掉 `THINKING` 类型消息。

换句话说：

- `ThinkingMessage` 更偏展示与记录
- 真正进入下一轮模型上下文的，是 `thinking` 工具对应的 `ToolResultMessage`

这个区分保证了：

- 思考可以展示
- 但不会让上下文里混入过多冗余 reasoning 噪声

---

## 9. 本项目当前提示流

当前本项目 `agent-base` prompt 的主线是：

```text
Analyze
 -> Decide
 -> Plan
 -> Retrieve
 -> Reflect
 -> Answer
```

这是一套“弱工作流约束”的 Agent Prompt。

### 9.1 Analyze

先识别：

- 用户目标
- 限制条件
- 是 direct lookup 还是 open-ended exploration
- 是 comparison 还是 multi-step research

### 9.2 Decide

做一个初步分流：

- 如果问题很简单，不需要 research，可以直接答
- 否则进入 retrieval mode

### 9.3 Plan

当前 prompt 要求：

- 开放探索
- 多主题比较
- 多次检索
- 用户明确要计划

这些情况下应该调用 `todo_write`

### 9.4 Retrieve

当前推荐的检索顺序是：

- `search_knowledge_keywords`
- `search_knowledge_base`

这里要特别注意：

- 当前项目没有单独的 `deep read` / `list_knowledge_chunks`
- 检索结果通常由工具直接拼装为 `<context>` 返回

所以当前项目并没有实现“命中候选 ID 后必须再做全文深读”这一步的强分层。

### 9.5 Reflect

当前 prompt 要求在关键 retrieval step 后调用 `thinking`：

- 判断证据是否足够
- 判断是否还缺信息
- 判断是否应该调整检索策略

但这仍然是 prompt 约束，不是 runtime 强校验。

### 9.6 Answer

只有在模型认为证据足够时，才进入最终回答。

当前项目的结束方式是：

- 本轮没有新的 tool call
- loop 结束
- 输出最终答案

当前并没有独立的 `final_answer` 工具。

### 9.7 当前 prompt 的边界

因此，当前项目虽然已经具备：

- 会规划
- 会检索
- 会反思
- 会多轮执行

但它仍然不是强阶段化流程。

缺少的典型强约束包括：

- 强制 preliminary reconnaissance
- 强制 deep read
- 强制 task completion gating
- 强制 final answer protocol

---

## 10. Go 项目 Prompt 工作流译解

你给出的 Go / WeKnora 风格 Prompt，工作流主线更强，明确是：

```text
Preliminary Reconnaissance
 -> Strategic Decision & Planning
 -> Disciplined Execution & Deep Reflection
 -> Final Synthesis
```

下面给出这套 workflow 的中文译解。

### 10.1 第一阶段：初步侦察

在回答问题或创建计划之前，必须先对知识库进行一次“深读测试”。

具体动作是：

1. 用 `grep_chunks` 做关键词检索
2. 用 `knowledge_search` 做语义检索
3. 只要命中了 `knowledge_ids` 或 `chunk_ids`，就必须立刻调用 `list_knowledge_chunks`
4. 在 think block 中分析全文内容：
   - 是否已经足以回答用户
   - 信息是完整还是部分覆盖

### 10.2 第二阶段：战略决策与规划

基于全文深读后的结果做决策：

- 如果证据已经充分、明确、无歧义，就直接回答
- 如果问题复杂、需要比较、存在缺口，就调用 `todo_write` 拆分研究任务

### 10.3 第三阶段：纪律化执行与深度反思

如果进入 plan 路线，就必须顺序执行每一个任务。

每个任务都要求：

1. Search
2. Deep Read
3. Deep Reflection
4. 如有必要，立刻做补救检索
5. 只有拿到足够证据，才能标记为 completed

### 10.4 第四阶段：最终综合

只有所有 `todo_write` 任务全部完成后，才允许：

- 综合所有全文证据
- 检查一致性
- 调用 `final_answer`

### 10.5 这套 Prompt 最强的几个 mandatory action

Go 工作流最强的地方，不只是“有阶段”，而是每个阶段都有必须动作：

- 检索命中后必须 Deep Read
- 有 todo plan 后必须顺序执行
- 每个子任务后必须反思有效性、缺口和补救动作
- 所有任务完成后才能综合
- 最后必须走 `final_answer`

因此，它比普通“请一步步思考”的 Prompt 更接近一份真正的工作流 SOP。

---

## 11. 本项目 vs Go 项目：工作流与过程幻觉对比

下面把两者并排对比。

| 维度 | 本项目当前 | Go 项目 Prompt | 说明 |
| --- | --- | --- | --- |
| 顶层 workflow | `Analyze -> Decide -> Plan -> Retrieve -> Reflect -> Answer` | `Reconnaissance -> Planning -> Execute/Deep Reflection -> Final Synthesis` | Go 的阶段边界更清晰 |
| 初步侦察 | 非强制 | 强制起步步骤 | Go 更强调“先摸清信息地形” |
| Deep Read | 无独立强制步骤 | 命中后必须读全文 | 这是当前差距最大的点之一 |
| todo 约束 | Prompt 要求顺序执行 | Prompt 强制顺序执行 | 两边都主要靠 Prompt，但 Go 约束更硬 |
| thinking 约束 | 关键检索后应反思 | 每个子任务后必须反思 | Go 的节奏更密 |
| 最终收束 | 无 `final_answer` | 必须 `final_answer` | Go 的结束协议更清晰 |
| todo 状态语义 | 轻量软状态 | 强化软状态 | Go 更强，但也不是硬状态机 |
| 思考承载 | reasoning adapter + `thinking` tool | Prompt 依赖 think block / tools | 本项目在工程机制上更稳 |
| runtime guard | 有 max iterations / context fallback | 仅看 Prompt 文本未体现 runtime guard | 只靠 Prompt 仍有风险 |

### 11.1 当前本项目强在哪里

当前本项目更强的地方不在 workflow 纪律，而在工程实现：

- 有真实的多轮 execute loop
- 有 function calling 闭环
- 有显式 `thinking` 工具
- 有 provider reasoning adapter
- 有 observation 回填
- 有 iteration / context overflow guard

也就是说，本项目的“思考能力承载机制”比单纯的 Prompt 更工程化。

### 11.2 Go 项目强在哪里

Go 项目更强的是 workflow discipline：

- 阶段清晰
- 强制 deep read
- 强制 task-by-task 执行
- 强制 completion gating
- 强制 final answer

所以如果只比较“提示流设计”，Go 项目明显更强。

### 11.3 什么是过程幻觉

这里要特别引入一个我们本次讨论反复强调的概念：过程幻觉。

过程幻觉不是“答案内容编错了”，而是：

- 模型声称自己执行了某个流程动作
- 但这个动作其实并没有被系统验证

典型表现包括：

- 说自己已经 deep read 了全文，其实只看了 snippet
- 说 todo 已经完成，其实还有 pending step
- 说已经综合了全部证据，其实只用了部分结果
- 说已经反思并确认充分，其实只是口头描述

### 11.4 为什么“强 Prompt + 弱 Guard”容易导致过程幻觉

如果一个 workflow 只靠 Prompt 要求：

- 你必须 deep read
- 你必须顺序执行
- 你必须完成所有任务后再综合

但系统运行时不检查这些条件，那么模型就可能只是“口头遵守”。

因此，强 Prompt 本身不等于低幻觉。

更准确地说：

```text
强 Prompt
+ 弱 runtime guard
= 更容易出现过程幻觉
```

这也是为什么后文的推荐目标方案，不能只停留在 Prompt 改写。

---

## 12. 推荐目标方案

综合当前本项目实现和 Go 工作流设计，推荐路线不是二选一，而是组合：

- 工作流纪律上，优先借鉴 Go 项目
- 思考承载机制上，保留本项目的 `thinking tool + reasoning adapter`

### 12.1 推荐目标 workflow

推荐目标链路可以写成：

```text
Query
 -> Preliminary Reconnaissance
 -> Deep Read
 -> Decide
 -> todo_write plan
 -> Execute task by task
 -> thinking after each key step
 -> all tasks completed
 -> Final Synthesis
 -> final_answer
```

### 12.2 为什么不建议只照抄 Go Prompt

原因很简单：

- Go Prompt 的 workflow discipline 很强
- 但如果不配 runtime guard，就容易出现过程幻觉

因此，后续增强不能只做“Prompt 翻译迁移”，还必须补系统能力。

### 12.3 推荐优先补的 guardrail

如果后续要把本项目升级为更强的 Progressive Agentic RAG Agent，建议优先补这些 guard：

1. `deep read` 工具  
   把“候选召回”和“全文深读”真正拆开。

2. `todo` 持久化状态  
   从“轻量软状态”升级为至少“可读、可更新、可校验”的任务状态。

3. 未完成任务禁止 final synthesis  
   在 runtime 层检查是否仍存在 pending / in_progress。

4. 检索后未反思不能直接进入最终回答  
   至少对关键 retrieval step 设置一个 reflection gate。

5. 显式 `final_answer`  
   把最终收束动作从“自然停止”升级为“显式结束协议”。

### 12.4 推荐保留的当前设计

当前项目有一些设计不应丢掉，尤其是：

- `thinking` 工具
- reasoning adapter
- OpenAI-compatible function calling 闭环
- observation 式错误回填
- max iteration / context overflow 保护

这些是当前项目的工程骨架，也是后续增强时最应该保留的部分。

---

## 13. 总结

`agentic_rag_app` 当前已经实现了一个具有分步思考雏形的 Agent。

它已经具备：

- 基于 OpenAI-Compatible SDK 的流式模型调用
- 普通内容、思考内容、工具调用三路分流
- 完整的 function calling 闭环
- ReAct 风格的多轮 execute loop
- 显式 `thinking` 工具
- 轻量计划工具 `todo_write`
- 异常尽量转 observation 的错误容错方式

但它仍然属于：

```text
弱工作流约束
+ 多轮工具调用
+ 工程化思考机制
```

而不是一个“强阶段化、强 guardrail”的 Progressive Agentic RAG Agent。

如果要进一步增强，推荐方向不是简单改 Prompt，而是：

- 工作流纪律借鉴 Go 项目
- 思考承载保留当前项目
- 用 runtime guard 把 Prompt 约束真正落到执行层

最终目标不是“让模型看起来更会思考”，而是让系统能够真实验证：

- 是否做了足够的检索
- 是否完成了必要的反思
- 是否满足了计划完成条件
- 是否已经到了可以收束输出最终答案的阶段

只有这样，一个“具有分步思考能力的 Agent”才不只是 Prompt 上的承诺，而会真正落实到代码执行路径中。
