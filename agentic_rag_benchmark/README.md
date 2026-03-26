# agentic_rag_benchmark

通用 Benchmark 自动化流水线包。

这个包是新评测体系的顶层入口，目标是把以下能力逐步沉淀出来：

- 离线标准化文档
- 提取稳定 evidence units
- 生成可迁移的 benchmark 数据包
- 兼容导入历史题库
- 对 package、schema 和历史数据做自动校验
- 后续承接真实运行编排与评测报告输出

当前第一阶段只提供：

- 顶层目录骨架
- 四个核心评测对象契约
- portable package 规范
- 历史题库兼容导入入口
- 基础校验 CLI

当前第二阶段新增：

- 复用 `docreader` 做文档标准化
- 从规范文本提取 `EvidenceUnit`
- 基于规则生成 `BenchmarkSample`
- 导出标准 benchmark 数据包
- 自动生成人工审阅版 `benchmark_suite.md`

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

目录职责如下：

- `cli/`
  命令行入口
- `docs/`
  契约与规范文档
- `fixtures/legacy/`
  历史样本和导入验证数据
- `outputs/`
  运行输出目录
- `packages/`
  标准 benchmark 数据包根目录
- `schemas/`
  JSON Schema 定义
- `tests/`
  单元测试

## 四个核心对象

### EvidenceUnit

稳定证据单元，不绑定具体 chunk。

- `evidence_id`
- `doc_path`
- `section_key`
- `section_title`
- `canonical_text`
- `anchor`
- `source_hash`
- `extractor_version`

### BenchmarkSample

一条完整评测样本。

- `sample_id`
- `question`
- `ground_truth`
- `ground_truth_contexts`
- `gold_evidence_refs`
- `tags`
- `difficulty`
- `suite_version`

### BuildDescriptor

一版知识库构建的描述对象。

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

更详细说明见 [core_object_contracts.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/docs/core_object_contracts.md)。

## 标准数据包目录

标准 package 目录规则：

```text
packages/<project_key>/<suite_version>/
```

每个 package 固定包含四个文件：

- `evidence_units.jsonl`
- `benchmark_suite.jsonl`
- `suite_manifest.json`
- `benchmark_suite.md`

其中：

- `jsonl` 是机器真源
- `md` 是人工审阅导出

更详细说明见 [portable_package_spec.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/docs/portable_package_spec.md)。

## 历史数据如何导入

第一阶段提供的是“历史题库兼容导入”，不是旧脚本兼容。

当前支持：

- 读取旧 JSONL 题库
- 识别 `question`
- 识别 `ground_truth` / `reference` / `answer`
- 识别 `ground_truth_contexts`
- 从旧 `meta.sources` 推导新的 `gold_evidence_refs`

仓库里附带了一份最小 legacy 样本：

[legacy_question_bank_sample.jsonl](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/fixtures/legacy/legacy_question_bank_sample.jsonl)

## CLI 用法

默认通过 `python -m` 运行。

### 1. 查看 schema

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli describe-schema
```

查看单个 schema：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli describe-schema --schema benchmark_sample
```

### 2. 校验历史数据

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

说明：

- 目录缺少固定文件会直接报错
- 空 JSON / JSONL 文件会跳过内容校验，但结构会判定为有效
- 非空文件会按 schema 做内容校验

### 4. 从文档构建标准 package

确保 `docreader_service` 已启动后，可以直接从单文件或目录生成 package：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli build-package \
  /absolute/path/to/source_docs \
  --project-key api_docs \
  --suite-version base_v1 \
  --docreader-base-url http://127.0.0.1:8090
```

可选参数：

- `--source-root`
  控制导出到 `doc_path` 的相对根目录
- `--package-root`
  控制 `packages/` 根目录，默认是当前 `agentic_rag_benchmark/`

生成完成后会在：

`packages/<project_key>/<suite_version>/`

下得到：

- `evidence_units.jsonl`
- `benchmark_suite.jsonl`
- `suite_manifest.json`
- `benchmark_suite.md`

## 第一阶段范围边界

第一阶段不会做这些事：

- 不接入 docreader
- 不生成 evidence units
- 不新增后端 API
- 不修改在线问答链路
- 不删除旧评测目录

这些内容会留在后续阶段继续补齐。
