# 核心评测对象契约

本文件定义第一阶段冻结的四个核心对象。

## EvidenceUnit

稳定证据单元，不绑定具体 chunk。

- `evidence_id`: 证据唯一标识
- `doc_path`: 原始文档路径
- `section_key`: 稳定 section 键
- `section_title`: section 标题
- `canonical_text`: 规范化证据正文
- `anchor`: 原文锚点
- `source_hash`: 证据内容哈希
- `extractor_version`: 提取器版本

## BenchmarkSample

一条完整评测样本。

- `sample_id`: 样本唯一标识
- `question`: 用户问题
- `ground_truth`: 标准答案
- `ground_truth_contexts`: 标准证据文本列表
- `gold_evidence_refs`: 标准证据引用列表
- `tags`: 标签列表
- `difficulty`: 难度
- `suite_version`: 所属题集版本

## BuildDescriptor

一版可评测知识库构建的元信息。

- `build_id`: 构建唯一标识
- `source_snapshot_id`: 原文快照标识
- `chunk_strategy_version`: 切分策略版本
- `embedding_model`: 向量模型
- `retriever_config`: 检索配置
- `status`: 构建状态
- `created_at`: 构建时间

## TurnExecutionSummary

真实单轮问答的结构化摘要。

- `session_id`: 会话标识
- `turn_id`: 轮次标识
- `user_question`: 本轮问题
- `final_answer`: 最终答案
- `finish_reason`: 结束原因
- `tool_calls`: 工具调用列表
- `retrieval_trace_ids`: 命中的检索 trace 标识列表
- `latency_ms`: 总耗时
- `provider`: 模型提供方
- `build_id`: 本轮绑定的 build
- `thinking_profile`: 思考档位
- `memory_enabled`: 是否开启记忆
