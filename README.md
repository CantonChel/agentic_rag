# Agentic RAG

一个面向内部协作与联调的 Agentic RAG 仓库，包含 Java 主服务、Python 文档解析服务、Benchmark 工具链，以及围绕检索、上下文管理、Memory 和评测的设计文档。

## 项目概览

这个仓库的目标不是只做一个“能检索”的 RAG Demo，而是把文档导入、异步解析、检索问答、会话上下文、长期记忆、评测闭环和可观测性串成一条可持续演进的工程链路。

当前仓库适合以下场景：

- 作为内部知识库问答与 Agent 检索实验平台
- 作为文档导入、切分、检索、问答的一体化联调环境
- 作为 Benchmark package 构建、导入、跑分与 RAGAS 评测的实验底座

## 当前功能

### 1. 知识库管理

- 支持知识库创建、查询、更新、删除，以及失败数据清理。
- 支持查看知识库下的文档、文档详情、分块结果和关联图片内容。
- 支持面向多知识库隔离的问答与检索场景，适合做不同数据域的并行验证。

### 2. 文档导入与解析

- 支持文件上传入库，并通过异步解析任务完成文档清洗与入库流程。
- 支持解析任务状态查询、失败重试和结果追踪。
- `docreader_service` 当前支持 `pdf`、`docx`、`txt`、`md`、`csv`、`json`、`html/htm` 等格式，并尽量输出结构化 Markdown 与图片引用。

### 3. 检索与 Agent 问答

- 支持文本/文件导入后的检索与基础 RAG 查询。
- 支持 Agent SSE 流式问答，并可切换 `OpenAI` 与 `MiniMax` 两种 provider。
- 支持工具调用、知识库作用域、benchmark build 作用域、memory 开关和 thinking profile 等执行控制，适合做 Agent 行为联调。

### 4. 会话与上下文

- 支持会话创建、列表、删除，以及消息历史查询。
- 支持 assistant 事件流回放，便于复盘一轮对话中的思考、工具和回答过程。
- 支持 session context、local execution context 等调试视图，方便观察上下文压缩和单轮执行状态。

### 5. Memory 能力

- 支持浏览和读取 memory 文件，区分 `global`、`fact`、`session_summary`、`session_context` 等范围。
- 支持 fact operation 审计查询，便于追踪事实写入、更新和验证过程。
- 适合用于验证“长期记忆 + 会话摘要 + 局部上下文”的组合策略。

### 6. Benchmark 与可观测性

- 支持 benchmark package 导入 build，并提供 build 列表与详情查询。
- 支持 turn summary 和 retrieval trace 查询，便于以真实后端执行结果作为评测真源。
- 仓库内提供独立的 benchmark CLI 与 RAGAS 评测链路，可用于构建、导入、跑分和报告输出。

## 系统组成

| 模块 | 作用 |
| --- | --- |
| `agentic_rag_app` | Java Spring Boot 主服务，负责知识库、检索、Agent、会话、Memory、Benchmark API 与内置联调页面 |
| `docreader_service` | Python FastAPI 文档清洗服务，负责多格式文档读取、Markdown 化和图片引用保留 |
| `agentic_rag_benchmark` | Benchmark 自动化流水线，负责 package 构建、兼容历史题库、真实链路跑分与报告输出 |
| `docs` | 存放架构说明、SOP、设计记录和联调结论 |

## 仓库结构

```text
.
├── agentic_rag_app/          # Java 主服务
├── docreader_service/        # Python 文档解析服务
├── agentic_rag_benchmark/    # Benchmark 工具链
├── docs/                     # 架构、SOP 与设计文档
├── scripts/                  # 启停、E2E、初始化脚本
├── docker-compose.yml        # 推荐启动方式
├── docker-compose.README.md  # Compose 补充说明
└── local-dev.README.md       # 本地无 Docker 启动说明
```

## 快速开始

推荐优先使用 Docker Compose 跑通整套服务，本地无容器开发再看补充说明。

### 1. 准备环境变量

至少配置一组可用的 LLM Key，用于 Agent 问答和相关能力验证。

```bash
cp .env.example .env
```

`.env` 中常见配置包括：

- `OPENAI_API_KEY`
- `MINIMAX_API_KEY`
- `DOCREADER_CALLBACK_SECRET`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`

### 2. 启动整套服务

```bash
docker compose up --build -d
```

默认会启动以下服务：

- `agentic-rag-app`: `http://localhost:8081`
- `docreader`: `http://localhost:8090`
- `postgres`: `localhost:5432`
- `redis`: compose 内部使用，不默认暴露到宿主机
- `minio`: `http://localhost:9000`
- `minio console`: `http://localhost:9001`

### 3. 基础检查

```bash
curl http://localhost:8090/healthz
./scripts/e2e_async_ingest.sh
```

### 4. 访问内置联调页面

启动后可以直接访问以下页面做联调与观察：

- `http://localhost:8081/`
- `http://localhost:8081/chat.html`
- `http://localhost:8081/knowledge.html`
- `http://localhost:8081/memory.html`

补充说明：

- Docker 启动细节见 [docker-compose.README.md](docker-compose.README.md)
- 本地无 Docker 启动见 [local-dev.README.md](local-dev.README.md)

## 常见工作流

### 1. 启动整套服务

用 `docker compose up --build -d` 启动 Java 主服务、docreader、Postgres、Redis 和 MinIO，然后通过内置页面或 API 做联调。

### 2. 上传文档并完成解析

先创建知识库，再上传文件进入异步解析链路；解析完成后查看文档、分块和图片结果。具体联调可结合 [docker-compose.README.md](docker-compose.README.md) 与 [docreader_service/README.md](docreader_service/README.md)。

### 3. 发起一次 Agent 问答 / 跑一次 benchmark

服务启动后可以直接做流式问答联调；需要评测时，可用 `agentic_rag_benchmark` 构建或导入标准 package，再跑真实链路 benchmark 并输出报告。详见 [agentic_rag_benchmark/README.md](agentic_rag_benchmark/README.md)。

## 文档导航

### 架构与存储检索

- [存储与检索整体架构](docs/storage-retrieval-architecture.md)

### 上下文与会话机制

- [聊天会话回放方案](docs/chat-session-replay.md)
- [分层上下文压缩机制](docs/context-management-layered-compression-sop.md)
- [Agent 分步思考方案](docs/agent-stepwise-thinking-sop.md)

### Memory

- [Memory Fact 操作校验](docs/memory-fact-operation-verification.md)
- [Memory Fact 操作可视化](docs/memory-fact-operation-visualization.md)

### 检索策略与 SOP

- [混合检索、Rerank 与 MMR](docs/hybrid-retrieval-rerank-mmr-sop.md)
- [DocReader 元素顺序规范](docs/docreader-element-order-sop.md)

### Benchmark

- [Benchmark 流水线说明](docs/benchmark-pipeline.md)
- [Benchmark 工具链 README](agentic_rag_benchmark/README.md)

### 本地开发与 Docker

- [Docker Compose 快速说明](docker-compose.README.md)
- [本地无 Docker 运行说明](local-dev.README.md)
- [文档目录索引](docs/README.md)
- [DocReader 服务说明](docreader_service/README.md)

## 当前状态与边界

- 这是一个偏内部协作和联调的工程仓库，首页重点是帮助团队成员快速建立全局认知，而不是替代专项设计文档。
- 当前主链路已经覆盖知识库管理、文档导入解析、检索问答、会话回放、Memory 浏览和 Benchmark 评测。
- 详细接口契约、上下文机制、检索策略和 benchmark 规范仍以 `docs/` 与子模块 README 为准。
- 根目录 README 保持“项目首页”定位，不承载完整环境变量手册、接口手册或所有实现细节。
