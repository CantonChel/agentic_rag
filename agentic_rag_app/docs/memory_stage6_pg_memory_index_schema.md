# 第六阶段：记忆 PG 持久化索引表结构

## 本阶段目标

这一阶段先把 memory 的 PG 持久化索引骨架立起来，但不切换现有 `memory_search` 的运行流量。

目标只有三个：

- 把 memory 专用索引表固定下来
- 把 scope 模型和 provider 指纹规则固定下来
- 把后续 `needsFullReindex` 需要比较的元信息写入位准备好

完成后，应用启动就能自动创建 memory 专用表和索引，Repository 也能对 `meta / files / chunks / embedding_cache` 完成最小读写。

## 4 张表结构

### `memory_index_meta`

一条 scope 一行，主键是：

- `scope_type`
- `scope_id`

核心字段：

- `index_version`
- `provider`
- `model`
- `provider_key_fingerprint`
- `sources_json`
- `scope_hash`
- `chunk_chars`
- `chunk_overlap`
- `vector_dims`
- `dirty`
- `last_sync_at`
- `last_error`

这张表只描述“这一份 scope 的索引配置和同步状态”，不直接参与搜索热路径。

### `memory_index_files`

一条 memory markdown 文件一行，唯一键是：

- `(scope_type, scope_id, path)`

核心字段：

- `path`
- `kind`
- `content_hash`
- `file_mtime`
- `indexed_at`

这张表用于文件级 skip。后续同步时只要 `path -> content_hash` 不变，就可以整文件跳过。

### `memory_index_chunks`

一条可检索 chunk 一行，唯一键采用：

- `(scope_type, scope_id, path, block_id, line_start, line_end, chunk_hash)`

核心字段：

- `path`
- `kind`
- `block_id`
- `line_start`
- `line_end`
- `chunk_hash`
- `content`

这张表会成为后续 memory 检索的主实体表，返回给工具的 `path / blockId / line range / snippet` 都从这里取。

### `memory_index_embedding_cache`

一条可复用 embedding 一行，唯一键是：

- `(provider, model, provider_key_fingerprint, chunk_hash)`

核心字段：

- `chunk_hash`
- `dimension`
- `vector_json`
- `content_hash`
- `updated_at`

它不绑定具体 scope，只绑定 embedding 配置和 chunk 文本 hash，用来支撑 chunk 级向量复用。

## Scope 组织方式

本轮把 scope 固定为两层：

- `global`
  - `scope_type = global`
  - `scope_id = __global__`
  - 来源固定为 `MEMORY.md`
- `user`
  - `scope_type = user`
  - `scope_id = <normalizedUserId>`
  - 来源固定为 `memory/users/<userId>/**/*.md`

`MemoryIndexScopeService` 负责：

- 生成 `global` 与 `user` scope
- 生成 `sources_json`
- 生成 `scope_hash`

这样后续 sync、watcher、search 都能统一用同一套 scope 描述，不再每个服务各自猜目录规则。

## 为什么不复用 KB `chunk / embedding`

知识库检索和 memory 检索虽然都需要 chunk 与向量，但它们的真相源和约束完全不同：

- KB 的主键组织围绕 `knowledge_id + chunk_id`
- memory 的主键组织围绕 `scope + path + block + line range`
- KB 需要服务导入包、文档解析、build 隔离
- memory 需要服务 markdown 文件、watcher、dirty、增量 sync、回源精读

如果直接复用 KB 表，会让 memory 额外背上不必要的 `knowledge_id / chunk_id / build` 语义，也很难自然表达：

- `scope_type`
- `scope_id`
- `path`
- `block_id`
- `line_start / line_end`
- `dirty`
- `content_hash`

所以这一阶段明确把 memory 索引从 KB 索引链路里拆开，后续只复用“向量检索和词法检索的思路”，不复用表模型。

## `needsFullReindex` 判定规则

后续 full reindex 会比较以下字段：

- `index_version`
- `provider`
- `model`
- `provider_key_fingerprint`
- `sources_json`
- `scope_hash`
- `chunk_chars`
- `chunk_overlap`
- `vector_dims`

只要其中任意一项与当前运行配置不一致，就判定该 scope 不能沿用原索引，需要走 full reindex。

普通文件内容变化不在这一级判定里处理，文件内容变化由 `memory_index_files.content_hash` 负责。

## Provider 指纹规则

本阶段新增 `MemoryIndexProviderProfileResolver`，它吸收现有 embedding 路由逻辑，统一产出：

- 当前生效的 provider
- 当前生效的 model
- `provider_key_fingerprint`

指纹只保存 API key 的哈希指纹，不保存明文 key。

同时它兼容当前已有的 openai/siliconflow 路由语义：

- 配置 `openai` 且 OpenAI key 缺失、SiliconFlow key 存在时，实际落到 `siliconflow`
- 其余情况按当前 embedding routing 逻辑确定

## 本阶段结果

本阶段完成后：

- 应用启动时会自动建出 memory 专用表
- PG 初始化会补齐 memory 检索需要的 trigram / tsvector / pgvector 索引
- Repository 已具备最小保存与读取能力
- 现有 `memory_search` 仍保持原逻辑，还没有切换到 PG 检索热路径
