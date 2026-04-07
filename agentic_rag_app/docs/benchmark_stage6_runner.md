# 第六阶段：新 Runner、真实评测闭环与旧包退场

> 历史说明：本文保留阶段性设计背景。凡涉及旧 `EvidenceUnit` 包、旧四件套 package，或把 authoring block 直接当 runtime chunk 的表述，均以历史方案处理。当前主链请以 `/Users/luolinhao/Documents/trae_projects/agentic_rag/docs/benchmark-pipeline.md` 为准。

## 目标

第六阶段把 benchmark 真正跑起来，并把 `agentic_rag_benchmark` 固化为唯一长期入口。

这一阶段之后：

- benchmark 通过真实 `/api/agent/*/stream` 跑单轮评测
- benchmark 后端真源固定为 `turn summary + retrieval trace`
- benchmark 统一输出 `json + md + RAGAS` 报告
- 历史 JSONL 题库只作为 `fixtures/legacy/` 中的兼容验证输入
- `agentic_rag_ragas` 退出仓库主流程

## runner 如何消费三本账

### 1. build ledger

- runner 通过 `buildId` 锁定要评测的知识库版本
- app 侧再把 `buildId` 解析成真实 `knowledgeBaseId`

### 2. retrieval ledger

- runner 不再解析 `<context>`
- runner 通过 `GET /api/benchmark/retrieval-traces` 读取结构化检索命中
- RAGAS 的 `contexts` 真源固定来自 `stage=context_output`

### 3. execution ledger

- runner 从 SSE 只提取 `turnId`
- 随后通过 `GET /api/benchmark/turn-summaries/{turnId}` 获取最终答案、finish reason、工具调用与 trace 引用

## 真实闭环流程

每条样本的固定流程是：

1. runner 生成唯一 `sessionId`
2. 调真实 `/api/agent/{provider}/stream`
3. SSE 中只提取 `turnId`
4. 用 `turnId` 查 `turn summary`
5. 用 `traceId` 或 `retrievalTraceIds` 查 `retrieval trace`
6. 组装样本级结果
7. 产出最终报告

runner 固定使用的运行约束为：

- `evalMode=SINGLE_TURN`
- `kbScope=BENCHMARK_BUILD`
- `memoryEnabled=false`
- `thinkingProfile=HIDE`

## 输出与兼容模式

runner 支持两类输入：

- `--package-dir`
- `--legacy-dataset`

legacy 模式会先把旧 JSONL 映射为 `BenchmarkSample`，再进入完全相同的真实执行链路。

每次运行固定输出：

- `run_meta.json`
- `samples_collected.jsonl`
- `benchmark_report.json`
- `benchmark_report.md`

RAGAS 成功时输出：

- `ragas_summary.json`
- `ragas_scores_per_sample.csv`

RAGAS 失败时输出：

- `ragas_error.json`

## 收口结果

第六阶段完成后：

- `agentic_rag_benchmark` 成为 benchmark 主入口
- `agentic_rag_ragas` 不再保留
- benchmark 真源正式切换为 `turn summary + retrieval trace`
