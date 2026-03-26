# portable benchmark package 规范

标准数据包目录规则为：

```text
packages/<project_key>/<suite_version>/
```

其中：

- `project_key` 使用通用命名，例如 `api_docs`、`product_manual`
- `suite_version` 使用版本命名，例如 `base_v1`、`smoke_v1`

## 固定文件

每个标准数据包必须包含以下四个文件：

- `evidence_units.jsonl`
- `benchmark_suite.jsonl`
- `suite_manifest.json`
- `benchmark_suite.md`

## 角色分工

- `evidence_units.jsonl`
  机器主档，存储稳定证据单元
- `benchmark_suite.jsonl`
  机器主档，存储评测样本
- `suite_manifest.json`
  机器主档，存储版本、文件清单和生成信息
- `benchmark_suite.md`
  人工审阅导出，不作为真源

## Manifest 字段

- `package_version`
- `project_key`
- `suite_version`
- `created_at`
- `generator_version`
- `files`

其中 `files` 必须显式列出四个固定文件名。
