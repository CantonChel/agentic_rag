# 第九阶段：记忆检索切换到 PG 持久化混合检索

## 本阶段目标

这一阶段正式把运行时 `memory_search` 从“扫描 markdown / 进程内拼装结果”切到 PG 持久化索引。

切换后固定保持两条边界：

- `memory_search`
  - 负责从 PG 持久化索引里做候选召回与排序
- `memory_get`
  - 负责按 `path + lineStart + lineEnd` 回源读取 markdown 原文

也就是说，搜索和精读彻底拆开，延续现有工具契约里的“先 search，再 get”。

## 搜索热路径

本阶段新增 `MemoryIndexSearchService`，并让 `MemoryRecallService.search()` 直接委托给它。

运行时热路径固定为：

1. 确定 scope
   - `global`
   - `user:<userId>`
2. 检查对应 scope 的 `meta.dirty`
   - dirty 或缺失时，异步请求后台 sync
   - 但当前已有旧 chunk 仍然直接参与检索
3. 生成 query embedding
4. 走向量候选召回
5. 走词法候选召回
6. 在服务层按 `path + blockId + lineStart + lineEnd` 去重融合
7. 输出现有 `MemorySearchHit`

因此 `meta / files` 仍然只属于后台 sync 路径，不进入搜索热路径。

## `chunks JOIN embedding_cache` 查询关系

向量检索时只碰两张表：

- `memory_index_chunks`
- `memory_index_embedding_cache`

查询关系固定为：

- `memory_index_chunks.chunk_hash = memory_index_embedding_cache.chunk_hash`
- 再叠加：
  - `provider`
  - `model`
  - `provider_key_fingerprint`

这样做有两个目的：

- 让搜索直接从 `chunks` 拿到可返回给工具的主实体字段
- 让向量仍然复用跨 scope 的 `embedding_cache`

因此真正返回给工具的字段都来自 `memory_index_chunks`：

- `path`
- `kind`
- `blockId`
- `lineStart`
- `lineEnd`
- `content`

`embedding_cache` 只负责提供向量距离分数，不承载结果真相。

## 词法与向量融合

本阶段没有引入 Lucene/BM25，词法侧继续保持 PG 轻量方案：

- `ILIKE`
- `to_tsvector(...) @@ plainto_tsquery(...)`
- `similarity(...)`

向量侧使用 pgvector 距离排序。

服务层融合规则固定为：

- 先分别取向量候选和词法候选
- 再按 `scope|path|blockId|lineStart|lineEnd` 语义去重
- 分数合成：
  - `0.68 * vectorScore + 0.32 * lexicalScore`
- 如果当前 embedding 不可用，则退化为词法分数

这样可以满足两个约束：

- 稳定保留用户长期决策、偏好、约束这类语义召回能力
- 不把 memory 检索升级成另一套复杂全文检索系统

## 为什么 `memory_get` 不做相似检索

`memory_get` 在这一阶段明确保持不变：

- 不查向量表
- 不做相似性召回
- 不重新排序候选
- 只按 `path + lineStart + lineEnd` 回源读取 markdown

原因有三个：

- `memory_search` 负责“找哪一段可能相关”
- `memory_get` 负责“把那一段原文准确拿回来”
- 这样能保证模型最终引用的是原始 markdown 的精确行号，而不是索引里的二次摘要

也正因为如此，工具约定继续要求模型优先：

1. 先运行 `memory_search`
2. 再对命中的候选运行 `memory_get`

## 本阶段结果

这一阶段完成后：

- `MemoryRecallService.search()` 已切到 PG 持久化混合检索
- 搜索不再依赖运行时扫描 memory markdown
- `memory_get` 仍然稳定回源读原文
- dirty scope 会在搜索时触发后台补同步，但旧索引结果立即可用
- 返回给工具的结果结构保持兼容，`memory_search -> memory_get` 契约不变
