# agentic_rag 项目存储与检索架构说明

## 文档目标与阅读方式

这篇文档按“从基础持久化到高级检索”的顺序，说明 `agentic_rag` 当前是如何把知识数据落到 PostgreSQL，并进一步利用 PostgreSQL 的全文索引、trigram 和 pgvector 完成检索的。

它主要解决 4 个问题：

1. 这个项目里，哪些数据进 PostgreSQL，哪些文件不进 PostgreSQL
2. 普通业务数据为什么可以直接通过 JPA 的 `save/find/delete` 操作表
3. 为什么 BM25、关键词模糊检索、向量检索又切到了 `JdbcTemplate + raw SQL`
4. 索引定义、查询 SQL 和 Java 代码之间到底是怎么一一对应起来的

建议按下面顺序阅读：

1. 先看“项目整体存储架构”
2. 再看“第一阶段：基础持久化链路”
3. 然后看“第二阶段：从 JPA 到 Raw SQL”
4. 最后看全文索引、trigram、pgvector 这三条检索链路

---

## 项目整体存储架构

这个项目的存储可以分成两层：

- PostgreSQL：存结构化元数据、解析任务、分块文本、embedding、检索索引
- 文件存储：存原始上传文件和解析出来的图片，后端可以是本地目录或 MinIO

主链路可以概括为：

```text
原始文件 -> local/MinIO
        -> knowledge.file_path 记到 PostgreSQL

上传任务 -> knowledge / parse_job
docreader 解析 -> chunk
embedding 模型 -> embedding

查询 -> 稀疏检索(BM25/关键词) + 稠密检索(向量)
    -> 融合
    -> rerank
```

### 哪些表是主角

当前最核心的几张表是：

- `knowledge_base`：知识库级别元数据
- `knowledge`：单个文档的元数据，记录 `filePath`、`fileHash`、`parseStatus`
- `parse_job`：异步解析任务状态
- `chunk`：切分后的文本块
- `embedding`：与 chunk 对应的向量和相关文本

其中：

- 原始文件本体不在 PostgreSQL 里，只在 `knowledge.filePath` 里留引用
- 真正参与检索的主表是 `chunk` 和 `embedding`

---

## 第一阶段：基础持久化链路

第一阶段只解决一件事：让项目能够稳定地把业务对象映射成 PostgreSQL 里的表记录。

这里依赖的是：

- Spring Boot
- Spring Data JPA
- Hibernate
- PostgreSQL JDBC Driver

### 1.1 基本关系

这条链路的核心关系是：

```text
Entity -> Repository -> Service -> Hibernate/JPA -> JDBC -> PostgreSQL
```

在这个项目里：

- 实体类描述“表长什么样”
- Repository 描述“能做哪些通用读写”
- Service 调用 Repository 完成实际持久化

### 1.2 为什么可以直接 `save/find/delete`

只要具备下面几个条件，Spring Data JPA 就能帮你生成 Repository 实现：

1. 实体类有 `@Entity`
2. 有主键字段 `@Id`
3. 项目 classpath 里有 `spring-boot-starter-data-jpa`
4. 数据源配置好
5. Spring 能扫描到实体类和 Repository

当前项目里这些条件都满足：

- JPA 依赖在 `agentic_rag_app/pom.xml`
- 数据源配置在 `agentic_rag_app/src/main/resources/application.properties`
- 启动入口是 `agentic_rag_app/src/main/java/com/agenticrag/app/AgenticRagAppApplication.java`

### 1.3 本项目里的实体示例

`chunk` 表对应的实体是 `ChunkEntity`。

它定义了：

- 表名：`chunk`
- 主键：`id`
- 业务字段：`chunkId`、`knowledgeId`、`content`、`metadataJson`
- 普通 B-Tree 索引：`knowledgeId`、`parentChunkId`

这说明 JPA 负责的是“表结构映射”和“通用持久化”，不是全文索引和向量索引。

### 1.4 本项目里的 Repository 示例

`ChunkRepository` 继承 `JpaRepository<ChunkEntity, Long>`，因此天然拥有：

- `save`
- `saveAll`
- `findById`
- `findAll`
- `delete`
- `count`

同时项目又加了两个派生查询：

- `deleteByKnowledgeId`
- `findByKnowledgeIdOrderByChunkIndexAsc`

也就是说，在普通表读写层，项目大部分时候不用手写 SQL。

### 1.5 本项目里的实际调用示例

`KnowledgeChunkPersistenceService` 里直接调用：

- `embeddingRepository.deleteByKnowledgeId(knowledgeId)`
- `chunkRepository.deleteByKnowledgeId(knowledgeId)`
- `chunkRepository.saveAll(chunks)`
- `embeddingRepository.saveAll(embeddings)`

这就是第一阶段的核心：普通业务写入由 JPA 完成。

---

## 第二阶段：从 JPA 到 Raw SQL

当项目进入检索阶段，仅靠 ORM 就不够了。

原因不是 JPA 不能查数据，而是 PostgreSQL 的检索能力大量依赖专有函数、专有操作符和专有扩展，例如：

- `to_tsvector(...)`
- `plainto_tsquery(...)`
- `ts_rank(...)`
- `similarity(...)`
- `word_similarity(...)`
- `::vector`
- `<->`
- `ivfflat`

这些都不是 JPA 擅长表达的查询。

所以项目的第二阶段变成：

```text
Entity -> Repository -> 普通 CRUD

JdbcTemplate -> Raw SQL -> 全文检索 / trigram / 向量检索
```

### 2.1 它们不是两套数据库连接

这一点很重要。

JPA 和 raw SQL 共用的是同一个 `DataSource`。

也就是说：

```text
DataSource
├─ Hibernate / JPA / Repository
└─ JdbcTemplate / raw SQL
```

所以区别不在“连哪个库”，而在“用哪种访问方式”：

- 普通表写入：JPA
- 检索类查询：`JdbcTemplate`

### 2.2 本项目里的典型分工

- `KnowledgeIngestService`、`KnowledgeChunkPersistenceService`：主要走 JPA
- `PostgresBm25Retriever`：走 `JdbcTemplate` 做全文检索
- `PostgresKeywordLikeRetriever`：走 `JdbcTemplate` 做关键词模糊检索
- `PostgresVectorStore`：走 `JdbcTemplate` 做 pgvector 向量检索

这就是当前代码的总体边界。

---

## PostgreSQL 检索能力准备

为了让 PostgreSQL 支持复杂检索，项目做了三层准备。

### 3.1 Java 依赖层

项目至少需要：

- `spring-boot-starter-data-jpa`
- PostgreSQL JDBC Driver

其中：

- 前者负责 JPA/Hibernate 能力
- 后者负责真正连 PostgreSQL

### 3.2 数据库扩展层

项目当前启用了两个 PostgreSQL 扩展：

- `vector`
- `pg_trgm`

作用分别是：

- `vector`：支持 pgvector 的向量类型、距离算子和 ANN 索引
- `pg_trgm`：支持 trigram 相似度检索

项目启动时会自动执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

如果数据库用户没有创建扩展的权限，也可以手工执行 `scripts/postgres_init.sql`。

### 3.3 初始化器层

项目有一个 `PostgresIndexInitializer`，只要配置：

```properties
rag.postgres.auto-index=true
```

应用启动时就会：

1. 创建扩展
2. 修复旧的 LOB/text 列问题
3. 创建全文、trigram、向量索引
4. 执行 `ANALYZE`

这意味着当前项目把“检索准备动作”放在启动过程里自动完成。

---

## 稀疏检索链路：全文与关键词模糊检索

这里的“稀疏”不是 learned sparse vector，而是传统词法检索。

当前项目主要有两条稀疏链路：

1. 全文检索链
2. trigram 模糊关键词链

### 4.1 全文索引 `idx_chunk_fts`

索引定义是：

```sql
CREATE INDEX IF NOT EXISTS idx_chunk_fts
ON chunk
USING GIN (to_tsvector('simple', content));
```

这里可以拆成三层理解。

#### `idx_chunk_fts` 是什么

这是索引名，不是语句名。

执行后，PostgreSQL 会在 `chunk` 表上创建一个叫 `idx_chunk_fts` 的索引对象。

#### `GIN` 是什么

`GIN` 是 PostgreSQL 的一种倒排索引型访问方法，适合：

- 全文检索
- 数组
- `jsonb`
- trigram

它适合“一个字段可以拆成很多 token”的场景。

#### `to_tsvector('simple', content)` 是什么

它表示：

- 用 `simple` 这个 text search 配置
- 把 `content` 转成全文检索向量 `tsvector`

可以粗略理解成：

```text
content -> 分词/规范化 -> token 集合
```

### 4.2 全文查询怎么写

项目里的查询在 `PostgresBm25Retriever` 里，核心形状是：

```sql
select ...
     , ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) as retrieval_score
from chunk c
where to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?)
order by ts_rank(to_tsvector('simple', c.content), plainto_tsquery('simple', ?)) desc
limit ?
```

这里对应关系很清楚：

- 建索引时：`to_tsvector('simple', content)`
- 查的时候：还是 `to_tsvector('simple', c.content)`
- 匹配操作符：`@@`
- 排序函数：`ts_rank(...)`

这就是“索引语义落实到代码”的第一条完整链路。

### 4.3 这是不是标准 BM25

严格说，不是。

`PostgresBm25Retriever` 这个名字延续了“BM25 风格召回”的业务语义，但实现上主要依赖的是：

- PostgreSQL Full Text Search
- `ts_rank`
- trigram fallback

所以更准确的说法是：

- 它是 PG FTS 排序链路
- 不是 Lucene 那种标准 BM25 公式实现

### 4.4 trigram 索引 `idx_chunk_content_trgm`

索引定义是：

```sql
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
ON chunk
USING GIN (content gin_trgm_ops);
```

#### `content gin_trgm_ops` 是什么意思

含义是：

- 对 `content` 这一列建索引
- 使用 `pg_trgm` 提供的 `gin_trgm_ops` 操作类

`trigram` 可以理解成把文本拆成连续 3 字符片段，再根据这些片段计算文本相似度。

#### trigram 模糊检索用来干什么

它主要用于：

- 模糊关键词命中
- 关键词打错一点也能召回
- 全文检索命中弱时的相似文本兜底

所以它本质上仍然属于“关键词检索增强”，不是语义检索。

### 4.5 项目里 trigram 怎么落到查询

`PostgresKeywordLikeRetriever` 的查询核心是：

```sql
where c.content ilike ? escape '\'
   or to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?)
   or c.content % ?
order by
   (case when c.content ilike ? escape '\' then 2.0 else 0.0 end)
 + ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', ?))
 + similarity(c.content, ?) desc
limit ?
```

这说明当前项目的关键词检索不是单一路径，而是混合打分：

- `ILIKE`：更像精确子串命中
- `@@`：全文命中
- `%` 和 `similarity(...)`：trigram 模糊相似

也就是说，trigram 索引的作用是“让模糊关键词检索更快、更稳”。

---

## 稠密检索链路：pgvector 向量检索

向量检索对应的是“稠密检索”。

当前项目的稠密链路是：

```text
chunk.content
  -> embeddingModel.embedTexts(...)
  -> embedding.vectorJson
  -> pgvector 表达式索引
  -> PostgresVectorStore 查询
```

### 5.1 embedding 是怎么生成并入库的

在 `DocreaderReadResultService` 里，项目会：

1. 从 chunk 里取出 `content`
2. 调 `embeddingModel.embedTexts(texts)`
3. 把每个向量序列化成 JSON 字符串
4. 写进 `EmbeddingEntity.vectorJson`

也就是说，当前 embedding 持久化形式不是 PostgreSQL 的原生 `vector` 列，而是文本列。

### 5.2 为什么当前是表达式索引

`EmbeddingEntity` 里：

- `vectorJson` 是 `text`

所以索引不能直接写成：

```sql
USING ivfflat (vector_json)
```

必须写成表达式索引：

```sql
USING ivfflat ((vector_json::vector))
```

意思是：

- 查询时把 `vector_json` 转成 `vector`
- 索引也建在这个转换表达式上

### 5.3 向量索引定义

当前索引定义是：

```sql
CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2
ON embedding
USING ivfflat ((vector_json::vector));
```

索引名里的 `l2` 对应当前查询使用的是 L2 距离路线。

### 5.4 查询怎么写

`PostgresVectorStore` 里的核心查询是：

```sql
select ...
     , (1.0 / (1.0 + (e.vector_json::vector <-> ?::vector))) as retrieval_score
from embedding e
join chunk c on c.chunk_id = e.chunk_id and c.knowledge_id = e.knowledge_id
join knowledge k on k.id = e.knowledge_id
where (? is null or k.knowledge_base_id = ?)
order by e.vector_json::vector <-> ?::vector
limit ?
```

这条 SQL 里有两个重点。

#### `vector_json::vector`

表示把文本形式的向量转换成 pgvector 的 `vector` 类型。

#### `<->`

这是 pgvector 的距离算子。

在当前上下文里，它走的是 L2 距离语义。

### 5.5 检索分和排序依据不是一回事

当前 SQL 里会计算：

```sql
1.0 / (1.0 + distance)
```

作为 `retrieval_score` 返回给上层。

但真正用于排序、真正最容易吃到索引的是：

```sql
order by distance asc
```

也就是：

- 显示给上层的分数：越大越像
- 底层索引排序依据：距离越小越像

这一点在理解向量检索时非常重要。

---

## 索引初始化器逐句拆解

`PostgresIndexInitializer.init()` 当前一共做了四类动作。

### 6.1 创建扩展

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

作用分别是：

- `vector`：启用 pgvector
- `pg_trgm`：启用 trigram 相似度能力

### 6.2 规范历史 LOB/text 列

项目里有一些列历史上可能被建成 `oid` 大对象列，而不是 `text`。

`normalizeLobTextColumn(...)` 会先查：

```sql
select udt_name from information_schema.columns ...
```

如果发现是 `oid`，就执行：

```sql
ALTER TABLE <table>
ALTER COLUMN <column>
TYPE text
USING convert_from(lo_get(<column>), 'UTF8')
```

它的目标不是检索，而是把旧数据修到当前代码能正确处理的文本格式。

### 6.3 修复已经被“文本化成 oid 编号”的旧行

`recoverTextifiedLargeObjectRows(...)` 会执行形如：

```sql
UPDATE <table>
SET <column> = convert_from(lo_get((<column>)::oid), 'UTF8')
WHERE <column> ~ '^[0-9]+$'
```

这一步相当于补救历史错误数据。

### 6.4 创建全文、trigram、向量索引

当前主索引是：

```sql
CREATE INDEX IF NOT EXISTS idx_chunk_fts
ON chunk
USING GIN (to_tsvector('simple', content));

CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
ON chunk
USING GIN (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2
ON embedding
USING ivfflat ((vector_json::vector));
```

项目还会给 memory 相关表创建同类索引。

### 6.5 `ANALYZE`

最后执行：

```sql
ANALYZE chunk;
ANALYZE embedding;
```

这不是创建索引，而是让 PostgreSQL 收集统计信息，帮助 planner 更合理地选择执行计划。

在当前项目里，因为 `init()` 是 `@PostConstruct`，所以只要应用启动且该 Bean 生效，启动时就会主动跑一次 `ANALYZE`。

除此之外，PostgreSQL 自身也可能在后续自动执行 analyze。

---

## 检索时到底怎么查、怎么排

可以把当前检索统一成三种排序方式。

### 7.1 全文检索

过滤条件：

```sql
where to_tsvector('simple', c.content) @@ plainto_tsquery('simple', ?)
```

排序方式：

```sql
order by ts_rank(...) desc
```

含义是：

- 先命中全文条件
- 再按全文匹配分数降序排

### 7.2 关键词模糊检索

过滤条件是混合的：

```sql
ILIKE
or @@
or %
```

排序方式是加权综合分：

```sql
(ILIKE bonus) + ts_rank_cd(...) + similarity(...) desc
```

含义是：

- 精确关键词命中有奖励
- 全文匹配有分
- trigram 模糊相似也有分

### 7.3 向量检索

过滤条件当前主要是 knowledge base 范围过滤：

```sql
where (? is null or k.knowledge_base_id = ?)
```

排序方式是距离升序：

```sql
order by e.vector_json::vector <-> ?::vector
limit ?
```

含义是：

- 向量距离越小，越靠前
- 显示层再把距离映射成 `retrieval_score`

### 7.4 为什么“距离升序”和“分数降序”都成立

这是因为：

- 底层真正的相似性度量先算距离
- 业务层更喜欢“分数越大越好理解”

所以常见做法是：

1. 用距离排序
2. 再把距离转成分数返回

当前项目正是这样。

---

## IVFFlat、HNSW 与调参说明

### 8.1 IVFFlat 和 HNSW 是什么关系

它们都是向量近邻搜索的 ANN 索引方法，但实现路线不同：

- IVFFlat：先分桶，再在候选桶里检索
- HNSW：基于图结构组织近邻关系

所以它们不是“同一种索引的两个参数”，而是两种不同的索引方法。

### 8.2 当前项目为什么是 IVFFlat

当前项目使用：

```sql
USING ivfflat ((vector_json::vector))
```

原因很直接：

- 当前实现目标是先把 pgvector 检索跑通
- IVFFlat 配置和理解门槛较低
- 代码里还没有围绕 HNSW 做额外说明或调参

### 8.3 如果切到 HNSW，哪些东西不变

查询的大形状通常仍然应保持：

```sql
order by e.vector_json::vector <-> ?::vector
limit ?
```

也就是说：

- 距离算子语义要保持一致
- 查询仍应是“距离升序 + `LIMIT`”

### 8.4 如果切到 HNSW，哪些东西要同步调整

至少要调整索引定义并重建索引。

概念上会变成：

```sql
CREATE INDEX ...
USING hnsw ((vector_json::vector) vector_l2_ops);
```

这里要注意两件事：

1. 索引方法从 `ivfflat` 变成 `hnsw`
2. operator class 要和距离语义保持一致

所以不是简单把关键字替换一下就万事大吉，而是要保证：

- 查询的距离算子
- 索引的 operator class
- 业务语义

三者一致。

### 8.5 `lists` 是什么

`lists` 是 IVFFlat 的重要参数，可以理解成“把向量空间切成多少个桶”。

典型写法是：

```sql
CREATE INDEX ...
USING ivfflat (...)
WITH (lists = 100);
```

### 8.6 当前为什么说“没有给 lists 做额外调参”

因为当前项目的索引定义只有：

```sql
USING ivfflat ((vector_json::vector))
```

没有写：

```sql
WITH (lists = ...)
```

也就是说当前用的是默认配置。

这表示项目目前更关注“功能先跑通”，还没有针对大规模向量数据做 IVFFlat 精调。

### 8.7 后续大数据量时要关注什么

如果数据规模继续增长，后续通常需要重新评估：

- `lists`
- 查询时的 `probes`
- 是否切换为 HNSW
- 向量列是否改成原生 `vector` 存储

---

## 当前实现的边界与注意事项

### 9.1 `PostgresBm25Retriever` 不是标准 BM25

它的核心实现依赖：

- `to_tsvector`
- `plainto_tsquery`
- `ts_rank`
- `word_similarity`

所以更准确说它是“PG FTS + fallback”，不是 Lucene 那种标准 BM25。

### 9.2 向量列当前以 JSON/text 持久化

当前 embedding 不是存成 PostgreSQL 原生 `vector` 列，而是：

- Java 侧序列化成 JSON 字符串
- PostgreSQL 查询时再 `::vector`

这带来的结果是：

- 实现简单
- 但查询和索引都依赖表达式转型

### 9.3 当前向量查询默认是 L2 距离

这体现在：

- 索引名：`idx_embedding_vector_l2`
- 查询算子：`<->`

如果未来改用 cosine 或 inner product，索引定义和查询语义都要一起调整。

### 9.4 当前没有显式按 `modelName/dimension` 过滤

`embedding` 表里其实记录了：

- `modelName`
- `dimension`

但当前向量查询没有按这些字段做额外过滤。

这隐含了一个前提：

- 当前库里默认只有兼容的 embedding 模型和统一维度

如果未来在同一表里混入不同模型或不同维度的向量，当前查询链路就需要进一步收紧约束。

### 9.5 自动建索引和自动 `ANALYZE` 的行为边界

当前项目会在应用启动时尝试：

- 建扩展
- 建索引
- 执行 `ANALYZE`

这带来的好处是本地启动和联调体验更顺滑，但也意味着：

- 运行环境需要足够权限
- 初始化逻辑和应用生命周期绑得比较紧

在更严格的生产环境里，这些动作有时会拆到专门的 DBA 或 migration 流程里执行。

---

## 总结

`agentic_rag` 当前的存储与检索架构，可以概括成一条逐层展开的链路：

1. 先用 JPA/Hibernate 解决普通业务对象到 PostgreSQL 表的映射与读写
2. 再用 `JdbcTemplate + raw SQL` 吃 PostgreSQL 的专有检索能力
3. 稀疏检索由全文索引和 trigram 索引承担
4. 稠密检索由 pgvector 的表达式索引承担
5. Java 代码中的查询形状与 PostgreSQL 中的索引表达式保持一致，索引语义才真正落地

所以，这个项目并不是“只用了 PostgreSQL 存数据”，而是逐步把 PostgreSQL 推到了两层角色：

- 第一层：关系型持久化存储
- 第二层：检索执行引擎

也正因为如此，理解这套实现时不能只看表结构，还必须同时看：

- 索引定义
- 查询 SQL
- Java 代码中的调用路径

只有把这三者串起来，才能真正看懂当前项目是如何从 JPA 持久化走到 PostgreSQL 稀疏/稠密混合检索的。
