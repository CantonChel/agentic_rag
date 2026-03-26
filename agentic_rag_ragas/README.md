# agentic_rag_ragas

自动化 RAGAS 评测模块（与 `agentic_rag_app` 同级目录）。

## 功能

- 自动调用 `agentic_rag_app` 的 `/api/agent/{provider}/stream`
- 自动读取会话消息 `/api/sessions/{sessionId}/messages`
- 自动提取 `search_knowledge_base` 工具返回的 `<context>` 作为 `contexts`
- 自动运行 RAGAS，并输出可追踪报告

## 目录

- `run_auto_ragas.py`: 主程序
- `run_auto_ragas.sh`: 一键执行（自动建 venv + 安装依赖）
- `requirements.txt`: Python 依赖
- `datasets/sample_eval_qa.jsonl`: 样例评测集
- `outputs/`: 评测输出目录

## 数据集格式

支持 `JSONL` / `JSON`。

每条样本建议包含：

- `id`: 样本 ID（可选）
- `question`: 问题（必填）
- `ground_truth` 或 `reference`: 参考答案（建议填写）
- `provider`: `openai|minimax`（可选，默认命令行 `--provider`）
- `user_id`: 会话用户（可选，默认命令行 `--user-id`）

示例（JSONL）：

```json
{"id":"q001","question":"什么是RAG？","ground_truth":"RAG 是先检索再生成。"}
```

## 前置条件

1. `agentic_rag_app` 正在运行（默认 `http://127.0.0.1:8081`）
2. 已配置可用的模型 key：
   - 给 app 用：`OPENAI_API_KEY`（或 minimax 配置）
   - 给 RAGAS 评估模型用：`OPENAI_API_KEY`

## 运行

```bash
cd /Users/luolinhao/Documents/trae_projects/agentic_rag/agentic_rag_ragas
./run_auto_ragas.sh \
  --dataset ./datasets/sample_eval_qa.jsonl \
  --base-url http://127.0.0.1:8081 \
  --provider openai

# 如需抓取 RAGAS judge 原始输出并自动做 schema 诊断
./run_auto_ragas.sh \
  --dataset ./datasets/sample_eval_qa.jsonl \
  --base-url http://127.0.0.1:8081 \
  --provider minimax \
  --trace-ragas-judge
```

## 输出

每次执行会生成一个新目录：`outputs/run_YYYYmmdd_HHMMSS/`

- `run_meta.json`: 本次运行元信息
- `samples_collected.jsonl`: 每个样本采集到的 `answer/contexts/error`
- `ragas_scores_per_sample.csv`: 样本级分数
- `ragas_summary.json`: 聚合分数
- `ragas_error.json`: RAGAS 阶段报错时生成
- `ragas_judge_trace.jsonl`: （可选）judge 每次调用的 prompt 与原始输出
- `ragas_judge_diagnostics.json`: （可选）自动诊断输出是否符合 RAGAS JSON schema

## 说明

- 若样本缺少 `ground_truth`，脚本会自动只跑不依赖参考答案的指标。
- 默认 `--tools` 开启，确保 agent 能调用 `search_knowledge_base`。
