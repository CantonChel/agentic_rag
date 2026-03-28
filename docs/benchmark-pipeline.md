# Benchmark 流水线使用说明

## 适用范围

这份文档说明本仓库里新的 benchmark 流水线应该怎么用，包括：

- 如何用 `restart_all.sh` 拉起本地依赖和服务
- 如何从文档生成标准 benchmark package
- 如何把 package 导入 `agentic_rag_app`
- 如何跑真实单轮 benchmark
- 如何查看输出报告

benchmark 的长期入口是 `agentic_rag_benchmark`，真实执行链路在 `agentic_rag_app`。

## 1. 启动本地服务

先在仓库根目录执行：

```bash
./scripts/restart_all.sh
```

这个脚本会启动：

- Redis
- PostgreSQL
- MinIO
- `docreader_service`
- `agentic_rag_app`

脚本默认日志目录：

- `.logs/docreader.log`
- `.logs/app-build.log`
- `.logs/app.log`

启动成功后，至少应满足：

```bash
curl http://127.0.0.1:8090/healthz
curl http://127.0.0.1:8081/actuator/health
```

预期返回分别类似：

- `{"ok":true}`
- `{"status":"UP"}`

说明：

- 请在正常终端里执行这个脚本。
- 当前脚本已经改成“先打包，再直接 `java -jar` 启动 app”，避免后台 `spring-boot:run` 在脚本结束后把真实 JVM 一起带掉。

## 2. 校验或构建标准 package

仓库根目录下统一用：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag python3 -m agentic_rag_benchmark.cli <command>
```

### 2.1 校验 package

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli validate-package \
  /absolute/path/to/packages/<project_key>/<suite_version>
```

### 2.2 从文档生成 package

`build-package` 会调用 `docreader_service`，把原始文档标准化后生成：

- `evidence_units.jsonl`
- `benchmark_suite.jsonl`
- `suite_manifest.json`
- `benchmark_suite.md`

示例：

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli build-package \
  /absolute/path/to/source_docs \
  --project-key api_docs \
  --suite-version base_v1 \
  --docreader-base-url http://127.0.0.1:8090
```

输出目录默认位于：

```text
agentic_rag_benchmark/packages/<project_key>/<suite_version>/
```

## 3. 把 package 导入 app

生成好的标准 package 需要先导入 `agentic_rag_app`，让 app 为它创建：

- `benchmark build ledger`
- 对应 `knowledgeBase`
- `knowledge / chunk / embedding`

导入接口：

```bash
curl -X POST http://127.0.0.1:8081/api/benchmark/builds/import-package \
  -H 'Content-Type: application/json' \
  -d '{
    "packagePath": "/absolute/path/to/agentic_rag_benchmark/packages/api_docs/base_v1"
  }'
```

返回结果里最重要的是：

- `buildId`
- `knowledgeBaseId`
- `status`

可以继续用下面的接口确认：

```bash
curl http://127.0.0.1:8081/api/benchmark/builds
curl http://127.0.0.1:8081/api/benchmark/builds/<buildId>
```

## 4. 跑真实 benchmark

### 4.1 标准 package 模式

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli run-benchmark \
  --package-dir /absolute/path/to/packages/api_docs/base_v1 \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --build-id <buildId> \
  --user-id benchmark-runner
```

### 4.2 legacy JSONL 模式

```bash
PYTHONPATH=/Users/luolinhao/Documents/trae_projects/agentic_rag \
python3 -m agentic_rag_benchmark.cli run-benchmark \
  --legacy-dataset /absolute/path/to/legacy.jsonl \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --build-id <buildId> \
  --user-id benchmark-runner
```

runner v1 的固定运行约束：

- `evalMode=SINGLE_TURN`
- `kbScope=BENCHMARK_BUILD`
- `memoryEnabled=false`
- `thinkingProfile=HIDE`

## 5. 输出报告在哪里看

每次执行会生成一个新目录：

```text
agentic_rag_benchmark/outputs/run_<timestamp>/
```

固定产物：

- `run_meta.json`
- `samples_collected.jsonl`
- `benchmark_report.json`
- `benchmark_report.md`

RAGAS 成功时额外产物：

- `ragas_summary.json`
- `ragas_scores_per_sample.csv`

RAGAS 执行失败时：

- `ragas_error.json`

注意：

- 即使 RAGAS 失败，只要主运行闭环成功，`benchmark_report.json` 和 `benchmark_report.md` 仍然是有效结果。

## 6. benchmark 现在认什么为真源

新的 benchmark 不再依赖：

- session messages
- replay
- 从 `<context>` 文本里做正则解析

当前固定真源是：

- `turn summary`
- `retrieval trace`

也就是说：

- 最终答案认 `turn summary.finalAnswer`
- 完成状态认 `turn summary.finishReason`
- 检索证据认 `retrieval trace`

## 7. 本地联调建议

最稳妥的一条本地闭环是：

1. 执行 `./scripts/restart_all.sh`
2. `build-package`
3. `POST /api/benchmark/builds/import-package`
4. `run-benchmark --package-dir ... --build-id ...`
5. 打开 `agentic_rag_benchmark/outputs/run_<timestamp>/benchmark_report.md`

如果只是验证服务是否恢复了“老能力”，可以直接做这条烟雾链路：

1. 创建一个知识库
2. 上传一个 markdown/docx/pdf/html 文档
3. 轮询 `/api/jobs/{jobId}` 直到 `success`
4. 用 `/api/tools/execute?knowledgeBaseId=...` 调 `search_knowledge_base`
5. 用 `/api/agent/minimax/stream?knowledgeBaseId=...` 发起一次真实问答

## 8. 相关文档

- [benchmark_stage3_kb_isolation.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_app/docs/benchmark_stage3_kb_isolation.md)
- [benchmark_stage4_retrieval_ledger.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_app/docs/benchmark_stage4_retrieval_ledger.md)
- [benchmark_stage5_execution_ledger.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_app/docs/benchmark_stage5_execution_ledger.md)
- [benchmark_stage6_runner.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_app/docs/benchmark_stage6_runner.md)
- [agentic_rag_benchmark/README.md](/Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_benchmark/README.md)
