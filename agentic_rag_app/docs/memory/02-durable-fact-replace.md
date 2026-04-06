# 阶段二：Durable Fact Replace

## 本阶段目标

- 将 durable memory 写入从“追加 daily markdown”切换为“按 bucket 的 durable fact upsert/replace”
- 引入结构化事实提取
- 引入 `ADD / UPDATE / NONE` 判定

## 结构化事实

- LLM 提取输出统一为：
  - `bucket`
  - `subject`
  - `attribute`
  - `value`
  - `statement`
- `fact_key = sha256(bucket | subject | attribute)`
- 单次 flush 最多保留 3 条 durable facts

## 候选查找

- 候选只在目标 bucket 文件内部查找
- 先按 `fact_key` 精确匹配，命中后只比较这一条
- miss 时做本地模糊 shortlist，最多 2 条
- shortlist 为空时直接 `ADD`

## 判定与写入

- 第二次 LLM 只输出：
  - `ADD`
  - `UPDATE`
  - `NONE`
- `ADD`：追加新的 `fact` block
- `UPDATE`：保留原 `block_id`，替换 block 内容并刷新 `updated_at`
- `NONE`：跳过写入

## 自测点

- 首次 durable fact 写入能落到正确 bucket 文件
- 同一 `fact_key` 更新时只保留一条 block
- `NONE` 分支不会产生额外写入
