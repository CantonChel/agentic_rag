# Benchmark Gold/Runtime 流水线说明

## 1. 目标

当前 benchmark 的固定设计原则是：

- `gold truth` 和 `runtime build` 分离
- gold 真源固定来自 `authoring_blocks + gold_block_refs`
- runtime 真源固定来自 app 导入 Gold 包后，对 `normalized_documents.normalized_text` 做真实切分得到的 `runtime chunks`
- “有没有命中出题依据”不依赖同一套切分，而依赖 `buildId + chunkId -> gold_block_ids` 的 build 级映射

这份文档是当前标准实现的总入口。旧的 `evidence_units / benchmark_suite / suite_manifest` 方案已经退居历史兼容路径，不再是标准主链。

## 2. 全链路数据流

```text
标准文件集
  -> source_manifest.json
  -> normalized_documents.jsonl
  -> authoring_blocks.jsonl
  -> block_links.jsonl
  -> samples.jsonl
  -> sample_generation_trace.jsonl
  -> gold_package_manifest.json
  -> review.md
  = Gold 包

Gold 包
  -> app import-package
  -> normalized_documents.normalized_text
  -> app 当前 splitter
  -> runtime knowledge / runtime chunks / embeddings / index
  -> build chunk mappings
  = runtime build

runtime build + benchmark runner
  -> agent stream
  -> turn summary
  -> retrieval traces
  -> chunk mappings 反查 gold_block_ids
  -> benchmark_report.json / benchmark_report.md / samples_collected.jsonl
  -> ragas_summary.json / ragas_scores_per_sample.csv
```

## 3. Gold 包八件套

标准 Gold 包目录：

```text
agentic_rag_benchmark/packages/<project_key>/<suite_version>/
```

固定文件：

- `source_manifest.json`
- `normalized_documents.jsonl`
- `authoring_blocks.jsonl`
- `block_links.jsonl`
- `samples.jsonl`
- `sample_generation_trace.jsonl`
- `gold_package_manifest.json`
- `review.md`

各文件语义：

- `source_manifest.json`
  记录这次标准文件集的来源、文件清单、哈希和 `source_set_id`
- `normalized_documents.jsonl`
  文档标准化结果，`normalized_text` 是 app runtime build 的直接输入
- `authoring_blocks.jsonl`
  出题最小单元，也是 gold 真源的块级基准
- `block_links.jsonl`
  块之间的 `prev_next / parent_child` 关系
- `samples.jsonl`
  题集；每条题的依据由 `gold_block_refs` 指向 `authoring_blocks`
- `sample_generation_trace.jsonl`
  记录一题是怎么从哪些 block 生成出来的
- `gold_package_manifest.json`
  Gold 包自身元信息和文件映射
- `review.md`
  人工审阅导出

## 4. 从标准文件集到 Gold 包

标准主链现在是：

1. 扫描标准文件集，生成 `source_manifest.json`
2. 通过 `docreader_service` 把原始文件标准化成 Markdown
3. 输出 `normalized_documents.jsonl`
4. 直接从 `NormalizedDocument.blocks` 生成 `authoring_blocks.jsonl`
5. 根据块的顺序和标题层级生成 `block_links.jsonl`
6. 用规则或模型从 authoring block 生成 `samples.jsonl`
7. 同步记录 `sample_generation_trace.jsonl`
8. 输出 `gold_package_manifest.json` 和 `review.md`

这里有一个非常重要的边界：

- `authoring_blocks` 只服务于出题、gold 真源、人工审阅
- 它们不会再被直接灌进 app 作为 runtime chunk

## 5. 从 Gold 包到 runtime build

`POST /api/benchmark/builds/import-package` 的当前语义是：

- 输入是 Gold 包目录
- runtime 构建输入是 `normalized_documents.jsonl`
- gold 映射输入是 `authoring_blocks.jsonl`

app 导入时做的事情：

1. 读取 Gold 包八件套
2. 为每个 `NormalizedDocument` 建一个 runtime `KnowledgeEntity`
3. 对 `normalized_text` 使用 app 当前真实 splitter 切分
4. 写入真实 `ChunkEntity / EmbeddingEntity / index chunks`
5. 为每个 runtime chunk 填充 `startAt / endAt`
6. 生成 build 级 `chunk mapping`

因此：

- `authoringBlockCount` 和 `runtimeChunkCount` 可以不同
- 改 query、prompt、tool 编排可以复用 build
- 只要改 splitter、embedding、ingest、storage、retriever，就必须重建 build

## 6. build 级 chunk mapping

命中判断现在依赖 build 级映射表：

```text
build_id
knowledge_id
chunk_id
doc_path
start_at
end_at
gold_block_ids
primary_gold_block_id
```

生成规则：

1. 用 `authoring_blocks.start_line/end_line` 把 gold block 还原成 `normalized_text` 上的字符区间
2. 用 runtime chunk 的 `startAt/endAt` 与同文档 gold block 做 overlap
3. overlap 字符数 `> 0` 就认为该 runtime chunk 命中该 gold block
4. overlap 最大的 block 作为 `primary_gold_block_id`

查询接口：

```text
GET /api/benchmark/builds/{buildId}/chunk-mappings
GET /api/benchmark/builds/{buildId}/chunk-mappings?chunkId=<chunkId>
```

## 7. runner 如何判断是否命中出题依据

runner 现在的流程是：

1. 读取 Gold 包里的 `samples.jsonl`
2. 用 sample.question 调 app 的真实单轮链路
3. 用 `turn summary` 读取最终答案、finish reason、latency、tool calls
4. 用 `retrieval traces` 读取各阶段命中的 runtime `chunkId`
5. 用 `chunk mapping` 把 runtime `chunkId` 反查成 `gold_block_ids`
6. 生成命中分析字段

固定字段：

- `target_gold_block_ids`
- `retrieved_chunk_ids`
- `retrieved_gold_block_ids_any_stage`
- `retrieved_gold_block_ids_context_output`
- `target_gold_block_hit_any_stage`
- `target_gold_block_hit_context_output`
- `matched_stage_set`
- `chunk_mapping_status`
- `chunk_mapping_error`

其中：

- RAGAS 的 `contexts` 仍然只来自 `stage=context_output` 的 `chunkText`
- 命中分析不改变 RAGAS 分数计算，只补充“检索有没有打中这道题的 gold 依据”

## 8. 可复用与必须重建的边界

### 可以复用的

- `source_manifest.json`
- `normalized_documents.jsonl`
- `authoring_blocks.jsonl`
- `samples.jsonl`
- 旧 build：当且仅当 runtime chunking / ingest / embedding / storage / retriever 没变

### 需要重建 Gold 包的

- 标准文件集变了
- 文档标准化逻辑变了
- authoring / sample 生成逻辑变了

### 需要重建 build 的

- splitter 参数变了
- embedding model 变了
- retrieval 或 index 结构变了
- chunk metadata / ingest 逻辑变了

## 9. 随机抽样

标准抽样命令：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli create-subset \
  --package-dir /absolute/path/to/packages/<project_key>/<suite_version> \
  --sample-count 10 \
  --seed 42 \
  --output-root /Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/packages \
  --suite-version-suffix smoke_10
```

抽样规则：

- `source_manifest.json` 原样保留
- `normalized_documents.jsonl` 原样保留
- `samples.jsonl` 只保留抽中的 sample
- `sample_generation_trace.jsonl` 只保留对应 sample
- `authoring_blocks.jsonl` 只保留抽中 sample 的 `gold_block_refs` 命中的 block
- `block_links.jsonl` 只保留两端 block 都存在的 link
- `gold_package_manifest.json` 和 `review.md` 重新生成

## 10. 报告与中间产物

每次 `run-benchmark` 会生成：

- `run_meta.json`
- `samples_collected.jsonl`
- `benchmark_report.json`
- `benchmark_report.md`

RAGAS 成功时额外生成：

- `ragas_summary.json`
- `ragas_scores_per_sample.csv`

各产物语义：

- `run_meta.json`
  一次 benchmark 运行的输入信息
- `samples_collected.jsonl`
  每条样本的原始执行结果，包含 tool calls、retrieval traces、命中分析字段
- `benchmark_report.json`
  总结版 JSON 报告，便于 console 和后续分析消费
- `benchmark_report.md`
  面向人工查看的报告，包含现有 execution/RAGAS 指标和 gold/runtime 命中分析说明
- `ragas_summary.json`
  RAGAS 各指标总体分数
- `ragas_scores_per_sample.csv`
  每样本 RAGAS 打分

## 11. 标准使用步骤

### 11.1 启动服务

```bash
./scripts/restart_all.sh
```

### 11.2 构建 Gold 包

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli build-package \
  /absolute/path/to/source_docs \
  --project-key api_docs \
  --suite-version base_v1 \
  --docreader-base-url http://127.0.0.1:8090
```

### 11.3 可选随机抽样

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli create-subset \
  --package-dir /absolute/path/to/packages/api_docs/base_v1 \
  --sample-count 10 \
  --seed 42 \
  --output-root /Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/packages \
  --suite-version-suffix smoke_10
```

### 11.4 导入 runtime build

```bash
curl -X POST http://127.0.0.1:8081/api/benchmark/builds/import-package \
  -H 'Content-Type: application/json' \
  -d '{
    "packagePath": "/absolute/path/to/packages/api_docs/base_v1_smoke_10"
  }'
```

### 11.5 跑 benchmark

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli run-benchmark \
  --package-dir /absolute/path/to/packages/api_docs/base_v1_smoke_10 \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --build-id <buildId> \
  --user-id benchmark-runner
```

## 12. 历史文档说明

`agentic_rag_app/docs/benchmark_stage3_kb_isolation.md` 到 `benchmark_stage6_runner.md` 里的部分表述仍保留阶段性背景，但凡涉及：

- `EvidenceUnit`
- 旧四件套 package
- `authoring block` 直接灌入 runtime chunk

都应视为历史方案。当前主链以本文和 `agentic_rag_benchmark` 实现为准。
