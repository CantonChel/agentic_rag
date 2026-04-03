# agentic_rag

`agentic_rag` 是一个面向知识检索与工具执行场景的 Agent/RAG 项目仓库。它把多轮 Agent 执行、文档解析、知识入库、混合检索、上下文管理和 benchmark 流水线放在同一个工程里，方便做端到端联调、验证和演进。

从根目录 `docs/` 的现有文档来看，这个仓库当前主要围绕以下几条主线展开：

- 一个具有流式输出、工具调用闭环和多轮执行能力的 Agent
- 一套面向长对话的分层上下文管理与压缩机制
- 一条基于 PostgreSQL 的知识存储、稀疏/稠密检索与融合链路
- 一个把原始文档解析成 RAG 友好 Markdown 的 docreader 服务
- 一套可导包、可跑分、可产出报告的 benchmark 流水线
- 一套面向前端体验的聊天会话精确回放机制

## 仓库结构

- `agentic_rag_app/`
  - 主应用，基于 Spring Boot，承担 Agent 执行、知识导入、检索、会话、记忆和 API 能力
- `docreader_service/`
  - 文档解析服务，负责把 PDF/DOCX 等输入统一转成适合 RAG 的 Markdown 与图片引用
- `agentic_rag_benchmark/`
  - benchmark 工具链，负责标准 package 构建、导入、运行和报告输出
- `agentic_rag_ragas/`
  - RAGAS 相关评测资源与运行支持
- `docs/`
  - 项目架构说明、SOP、联调结论和使用说明
- `scripts/`
  - 本地联调、服务拉起和辅助脚本
- `memory/`
  - 运行期记忆文件目录

## 核心能力

### 1. Agent 执行闭环

项目里的 Agent 不是一次性问答器，而是一个“模型流式输出 -> 工具调用 -> 工具结果回填 -> 下一轮继续推理”的执行循环。核心能力包括：

- 流式读取 LLM 输出
- 区分正文、思考内容和工具调用
- 把工具结果重新写回上下文
- 在多轮 loop 中持续推进直到收尾

对应文档：

- [`docs/agent-stepwise-thinking-sop.md`](docs/agent-stepwise-thinking-sop.md)

### 2. 分层上下文管理

上下文不是简单无限追加，而是拆成三层协作：

- 会话级上下文：负责跨轮 continuity
- 单轮执行上下文：负责当前轮真实执行
- 存储级兜底裁剪：负责防止历史无限膨胀

这套设计的重点是把“跨轮保留的结果”和“当前轮执行轨迹”分开，既尽量保住连续性，也减少工具噪声污染下一轮。

对应文档：

- [`docs/context-management-layered-compression-sop.md`](docs/context-management-layered-compression-sop.md)

### 3. 存储与检索架构

项目当前采用“文件存储 + PostgreSQL”双层架构：

- 原始文件与图片资源落文件系统或对象存储
- 结构化元数据、分块文本、embedding 和检索索引落 PostgreSQL

检索链路同时覆盖：

- 稀疏检索：全文检索、关键词模糊检索
- 稠密检索：pgvector 向量检索
- 融合与排序：RRF、rerank，以及后续 MMR 设计方向

对应文档：

- [`docs/storage-retrieval-architecture.md`](docs/storage-retrieval-architecture.md)
- [`docs/hybrid-retrieval-rerank-mmr-sop.md`](docs/hybrid-retrieval-rerank-mmr-sop.md)

### 4. 文档解析与 RAG 友好输出

`docreader_service` 的目标不是像阅读器一样复刻版式，而是尽量保持文字、表格、图片的阅读顺序，并输出统一 Markdown 契约，便于后续切块、向量化和检索。

核心约束包括：

- PDF 按页面阅读顺序组织元素
- DOCX 按文档流顺序组织元素
- 表格统一转 Markdown table
- 图片内容通过 `imageRefs` 单独返回

对应文档：

- [`docs/docreader-element-order-sop.md`](docs/docreader-element-order-sop.md)

### 5. Benchmark 与评测流水线

仓库内置 benchmark 流水线，支持：

- 拉起本地依赖与服务
- 从原始文档构建标准 benchmark package
- 导入 `agentic_rag_app`
- 触发真实 benchmark 运行
- 生成报告与 RAGAS 结果

对应文档：

- [`docs/benchmark-pipeline.md`](docs/benchmark-pipeline.md)

### 6. 聊天会话精确回放

为了改善前端多轮体验，项目引入了“最近活跃复用 + 精确回放”机制，用 replay 事件流恢复 assistant 侧的真实在线时序，尽量避免刷新后历史碎片化。

对应文档：

- [`docs/chat-session-replay.md`](docs/chat-session-replay.md)

## 快速启动

如果你只是想先把整套本地依赖拉起来，当前文档给出的统一入口是：

```bash
./scripts/restart_all.sh
```

这个脚本会启动本地联调依赖和核心服务。根据 benchmark 文档，常见健康检查包括：

```bash
curl http://127.0.0.1:8090/healthz
curl http://127.0.0.1:8081/actuator/health
```

更完整的 package 构建、导入、benchmark 运行说明请直接看：

- [`docs/benchmark-pipeline.md`](docs/benchmark-pipeline.md)

## 建议阅读顺序

如果你是第一次接手这个仓库，推荐按下面顺序阅读：

1. [`docs/storage-retrieval-architecture.md`](docs/storage-retrieval-architecture.md)
2. [`docs/hybrid-retrieval-rerank-mmr-sop.md`](docs/hybrid-retrieval-rerank-mmr-sop.md)
3. [`docs/agent-stepwise-thinking-sop.md`](docs/agent-stepwise-thinking-sop.md)
4. [`docs/context-management-layered-compression-sop.md`](docs/context-management-layered-compression-sop.md)
5. [`docs/docreader-element-order-sop.md`](docs/docreader-element-order-sop.md)
6. [`docs/chat-session-replay.md`](docs/chat-session-replay.md)
7. [`docs/benchmark-pipeline.md`](docs/benchmark-pipeline.md)

## 文档索引

根目录 `docs/README.md` 提供了简要索引：

- [`docs/README.md`](docs/README.md)

当前主要文档：

- [`docs/agent-stepwise-thinking-sop.md`](docs/agent-stepwise-thinking-sop.md)
- [`docs/context-management-layered-compression-sop.md`](docs/context-management-layered-compression-sop.md)
- [`docs/docreader-element-order-sop.md`](docs/docreader-element-order-sop.md)
- [`docs/hybrid-retrieval-rerank-mmr-sop.md`](docs/hybrid-retrieval-rerank-mmr-sop.md)
- [`docs/storage-retrieval-architecture.md`](docs/storage-retrieval-architecture.md)
- [`docs/chat-session-replay.md`](docs/chat-session-replay.md)
- [`docs/benchmark-pipeline.md`](docs/benchmark-pipeline.md)
