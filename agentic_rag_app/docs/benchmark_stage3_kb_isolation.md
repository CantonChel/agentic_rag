# 第三阶段：Build Ledger 与知识库隔离

> 历史说明：本文保留阶段性设计背景。凡涉及旧 `EvidenceUnit` 包、旧四件套 package，或把 authoring block 直接当 runtime chunk 的表述，均以历史方案处理。当前主链请以 `/Users/luolinhao/Documents/trae_projects/agentic_rag/docs/benchmark-pipeline.md` 为准。

## 目标

第三阶段把 `agentic_rag_benchmark` 产出的标准 package 导入 `agentic_rag_app`，并在应用侧生成独立的 `knowledgeBase`，让评测时可以显式锁定某一个 build 对应的知识库。

这一阶段的运行时隔离主键是 `knowledgeBaseId`：

- `buildId` 仍然是 benchmark 账本身份
- `knowledgeBaseId` 是真实检索作用域
- 不传 `knowledgeBaseId` 时，仍保持原来的全局检索兼容行为

## package 导入映射

导入时固定采用以下映射：

- `doc_path -> KnowledgeEntity`
- `EvidenceUnit -> ChunkEntity`
- `chunkId = evidence_id`
- `buildId -> knowledgeBaseId`

导入接口：

- `POST /api/benchmark/builds/import-package`
- `GET /api/benchmark/builds`
- `GET /api/benchmark/builds/{buildId}`

## 检索隔离

`knowledgeBaseId` 已经打通到以下链路：

- `AgentController -> AgentStreamingService`
- `LlmController -> StreamingChatService`
- `ToolController -> ToolExecutionContext`
- `KnowledgeSearchTool / KeywordLikeSearchTool`
- `HybridRetriever / DenseVectorRetriever`
- `LuceneBm25Retriever / PostgresBm25Retriever / PostgresKeywordLikeRetriever`
- `InMemoryVectorStore / PostgresVectorStore`

## 启动重建补丁

为了支持多 build 共存后的重启恢复，本阶段还修正了内存索引重建：

- embedding 启动重建改为按 `knowledgeId + chunkId` 复合键装载，避免同名 `chunkId` 串向量
- 重建时会为 chunk metadata 回填 `knowledge_base_id`，保证重启后 `knowledgeBaseId` 过滤仍然生效

## 已验证能力

- 同一 package 可导入成多个独立 build 并共存
- `knowledgeBaseId` 可从 HTTP 入口一路透传到工具与检索器
- 指定 `knowledgeBaseId` 后，检索结果只来自目标知识库
- 应用重启后的内存索引重建仍能保持多 build 隔离
