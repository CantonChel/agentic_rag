# agentic_rag_benchmark

通用 Benchmark 自动化流水线包。

当前第六阶段完成后，这个包已经是仓库内唯一的长期 benchmark 入口，负责：

- 复用 `docreader` 做文档标准化
- 生成 Gold 包八件套
- 生成可迁移的标准 benchmark package / 子集 package
- 兼容导入历史 JSONL 题库
- 调真实 `agentic_rag_app` 单轮链路跑分
- 读取 `turn summary + retrieval trace` 作为后端真源
- 通过 `build chunk mapping` 反查命中的 `gold_block_ids`
- 输出统一的 `json + md + RAGAS` 报告

旧的 `agentic_rag_ragas` 已退场，不再作为默认入口。

## 目录结构

```text
agentic_rag_benchmark/
├── README.md
├── requirements.txt
├── cli/
├── docs/
├── fixtures/
│   └── legacy/
├── outputs/
├── packages/
├── schemas/
└── tests/
```

- `cli/`
  benchmark 命令入口
- `docs/`
  契约与 package 规范文档
- `fixtures/legacy/`
  历史验证样本
- `outputs/`
  runner 输出目录
- `packages/`
  标准 benchmark package 根目录
- `schemas/`
  JSON Schema
- `tests/`
  Python 单元测试

## 核心对象

### SourceManifest

标准文件集清单。

- `source_set_id`
- `project_key`
- `source_root`
- `file_count`
- `files`
- `created_at`

### AuthoringBlock

Gold 真源的最小出题单元。

- `block_id`
- `doc_path`
- `section_key`
- `section_title`
- `block_type`
- `heading_level`
- `text`
- `anchor`
- `source_hash`
- `start_line`
- `end_line`

### BenchmarkSample

一条可迁移评测样本。

- `sample_id`
- `question`
- `ground_truth`
- `ground_truth_contexts`
- `gold_block_refs`
- `tags`
- `difficulty`
- `suite_version`

### BuildDescriptor

一版可评测知识库构建的描述对象。

- `build_id`
- `source_snapshot_id`
- `chunk_strategy_version`
- `embedding_model`
- `retriever_config`
- `status`
- `created_at`

### TurnExecutionSummary

一次真实单轮问答的结构化摘要。

- `session_id`
- `turn_id`
- `user_question`
- `final_answer`
- `finish_reason`
- `tool_calls`
- `retrieval_trace_ids`
- `latency_ms`
- `provider`
- `build_id`
- `thinking_profile`
- `memory_enabled`

详细字段说明见 [core_object_contracts.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/docs/core_object_contracts.md)。

## 标准 Package

标准 package 目录规则：

```text
packages/<project_key>/<suite_version>/
```

每个 package 固定包含：

- `source_manifest.json`
- `normalized_documents.jsonl`
- `authoring_blocks.jsonl`
- `block_links.jsonl`
- `samples.jsonl`
- `sample_generation_trace.jsonl`
- `gold_package_manifest.json`
- `review.md`

其中：

- `authoring_blocks + gold_block_refs` 是 gold 真源
- `normalized_documents` 是 app runtime build 的直接输入
- `review.md` 是人工审阅导出

详细规范见 [portable_package_spec.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/docs/portable_package_spec.md)。

## CLI 用法

默认通过 `python -m` 运行。

### 1. 查看 schema

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli describe-schema
```

### 2. 校验历史题库

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli validate-legacy-dataset \
  /Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/legacy_question_bank_sample.jsonl
```

### 3. 校验标准 package

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli validate-package \
  /absolute/path/to/packages/<project_key>/<suite_version>
```

### 4. 从文档构建标准 package

确保 `docreader_service` 可用后：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli build-package \
  /absolute/path/to/source_docs \
  --project-key api_docs \
  --suite-version base_v1 \
  --docreader-base-url http://127.0.0.1:8090
```

### 5. 跑真实 benchmark

标准 package 模式：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli run-benchmark \
  --package-dir /absolute/path/to/packages/<project_key>/<suite_version> \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --build-id <build_id> \
  --user-id benchmark-runner
```

legacy 兼容模式：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli run-benchmark \
  --legacy-dataset /absolute/path/to/legacy.jsonl \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --build-id <build_id> \
  --user-id benchmark-runner
```

runner 固定使用：

- `evalMode=SINGLE_TURN`
- `kbScope=BENCHMARK_BUILD`
- `memoryEnabled=false`
- `thinkingProfile=HIDE`

## RAGAS Judge 模型配置

RAGAS 评测使用 OpenAI 兼容协议的 Judge LLM，环境变量优先级如下：

- API Key: `RAGAS_JUDGE_API_KEY` -> `DEEPSEEK_API_KEY` -> `MINIMAX_API_KEY`
- Base URL: `RAGAS_JUDGE_BASE_URL` -> `DEEPSEEK_BASE_URL` -> `MINIMAX_BASE_URL` -> 默认 `https://api.deepseek.com/v1`
- Model: `RAGAS_JUDGE_MODEL` -> `DEEPSEEK_MODEL` -> `MINIMAX_MODEL` -> 默认 `deepseek-chat`

如果你要固定使用 DeepSeek Chat，最小配置如下：

```bash
export DEEPSEEK_API_KEY="<your_deepseek_key>"
export DEEPSEEK_BASE_URL="https://api.deepseek.com/v1"
export DEEPSEEK_MODEL="deepseek-chat"
```

`python -m agentic_rag_benchmark.cli ...` 和 `python -m agentic_rag_benchmark.console_server`
会自动向上查找最近的 `.env` 并补齐缺失配置；已经显式导出的非空环境变量不会被覆盖。

## 真实闭环真源

新 runner 不再依赖：

- session messages
- replay
- `<context>` 正则解析

统一改为读取：

- `turn summary`
- `retrieval trace`

也就是说：

- 最终答案只认 `turn summary.finalAnswer`
- 完成状态只认 `turn summary.finishReason`
- 检索上下文只认 `retrieval trace` 中 `stage=context_output`

## 输出文件

每次运行会在 `outputs/run_<timestamp>/` 下生成：

- `run_meta.json`
- `samples_collected.jsonl`
- `benchmark_report.json`
- `benchmark_report.md`

RAGAS 成功时还会生成：

- `ragas_summary.json`
- `ragas_scores_per_sample.csv`

RAGAS 执行失败时会额外生成：

- `ragas_error.json`

## 历史题库

保留在新包内的历史验证样本位于：

- [legacy_question_bank_sample.jsonl](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/legacy_question_bank_sample.jsonl)
- [weknora_question_bank_single.jsonl](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/weknora_question_bank_single.jsonl)
- [weknora_question_bank_multi.jsonl](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/weknora_question_bank_multi.jsonl)
- [sample_eval_qa.jsonl](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/sample_eval_qa.jsonl)

这些文件仅作为兼容验证输入，不进入 feature 默认命名。
