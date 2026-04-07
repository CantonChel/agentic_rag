# 阶段 2：单机 Compose 完整闭环（独立 infra 版）

## 目标

这一阶段的目标不是“把服务点亮”就算完，而是要真正跑通一次完整 ingest：

- `app`
- `postgres`
- `redis`
- `minio`
- `docreader`
- `mock-embedding`

最后的验收标准是：

- 服务都能启动
- `app`、`docreader`、`mock-embedding` 能通过 HTTP 检查
- 上传文档后，异步任务能走到 `success`
- PostgreSQL 的 `embedding` 表里真的有数据

## 先理解目录

这一阶段有两个目录：

- 模板目录：`/home/s/agentic_lab/app/docs/docker-lab/examples/phase2`
- 运行目录：`/home/s/agentic_lab/infra/phase2`

不要直接在模板目录里运行 `docker compose`。  
这些模板里的相对路径，是按运行目录 `/home/s/agentic_lab/infra/phase2` 设计的。

## Step 1：确认模板已同步到远端

```bash
cd /home/s/agentic_lab/app
find docs/docker-lab/examples/phase2 -maxdepth 3 -type f | sort
```

作用：

- 确认阶段 2 的模板文件已经在远端仓库里

预期：

- 至少能看到 `compose.yml`、`.env.example`、`mock-embedding` 目录

## Step 2：准备运行目录

如果你以前已经建过旧的 `phase2` 目录，先备份：

```bash
if [ -d /home/s/agentic_lab/infra/phase2 ]; then mv /home/s/agentic_lab/infra/phase2 /home/s/agentic_lab/infra/phase2.bak.$(date +%Y%m%d-%H%M%S); fi
```

然后创建新目录并复制模板：

```bash
mkdir -p /home/s/agentic_lab/infra/phase2
cp -R /home/s/agentic_lab/app/docs/docker-lab/examples/phase2/. /home/s/agentic_lab/infra/phase2/
cd /home/s/agentic_lab/infra/phase2
```

作用：

- 把仓库里的模板变成远端实际运行目录

## Step 3：准备 `.env`

```bash
cp .env.example .env
sed -n '1,220p' .env
```

重点看这些字段：

- `APP_BUILD_CONTEXT`
- `DOCREADER_BUILD_CONTEXT`
- `APP_HOST_PORT`
- `DOCREADER_HOST_PORT`
- `MOCK_EMBEDDING_HOST_PORT`
- `PG_HOST_PORT`
- `MINIO_API_HOST_PORT`
- `MINIO_CONSOLE_HOST_PORT`
- `RAG_EMBEDDING_PROVIDER`
- `RAG_EMBEDDING_SILICONFLOW_BASE_URL`

这一阶段默认已经帮你选好了本地 mock 路线：

- `RAG_EMBEDDING_PROVIDER=siliconflow`
- `RAG_EMBEDDING_SILICONFLOW_BASE_URL=http://mock-embedding:8080/v1`
- `SILICONFLOW_API_KEY=dummy`

这意味着：

- 不需要真实第三方 key
- embedding 走本地 `mock-embedding`

## Step 4：检查端口占用

```bash
ss -ltn | rg ':(38081|39090|38089|39000|39001|35432)\b' || true
```

作用：

- 确认阶段 2 端口没有撞到远端现有服务

如果有输出：

- 先记下是哪个端口冲突
- 回到 `.env` 改对应 `*_HOST_PORT`

## Step 5：先做一次 compose 配置校验

```bash
docker compose --env-file .env config >/tmp/docker-lab-phase2.config
tail -n 20 /tmp/docker-lab-phase2.config
```

作用：

- 先检查变量有没有正确展开
- 在真正启动前尽早发现拼写和缩进问题

## Step 6：启动阶段 2 拓扑

```bash
docker compose --env-file .env up -d --build
```

```bash
docker compose --env-file .env ps
```

预期：

- `postgres` 是 `healthy`
- `redis` 是 `healthy`
- `app`、`docreader`、`minio`、`mock-embedding` 是 `running`

说明：

- 第一次启动可能会慢，因为会构建 Java 和 Python 镜像

## Step 7：看关键日志

```bash
docker compose --env-file .env logs --tail=120 postgres
docker compose --env-file .env logs --tail=120 redis
docker compose --env-file .env logs --tail=120 docreader
docker compose --env-file .env logs --tail=120 mock-embedding
docker compose --env-file .env logs --tail=120 app
```

重点看：

- app 有没有成功连上 `postgres`
- app 有没有成功连上 `redis`
- app 有没有成功连上 `docreader`
- app 有没有把 embedding base url 指到 `mock-embedding`

## Step 8：做最小 HTTP 验收

### 8.1 mock-embedding

```bash
curl -fsS http://127.0.0.1:38089/healthz
```

### 8.2 docreader

```bash
curl -fsS http://127.0.0.1:39090/healthz
```

### 8.3 app

```bash
curl -i http://127.0.0.1:38081/api/jobs/not-found
```

预期：

- 返回 `200` 或 `404` 都可以
- 重点是 app 已经能正常接收 HTTP 请求

## Step 9：跑完整 ingest 验收

复用仓库里现成的脚本，只改端口：

```bash
APP_BASE_URL=http://127.0.0.1:38081 \
DOCREADER_BASE_URL=http://127.0.0.1:39090 \
KB_ID=kb-phase2 \
bash /home/s/agentic_lab/app/scripts/e2e_async_ingest_local.sh
```

预期：

- 上传成功
- 轮询最终进入 `success`

如果这一步失败：

- 先看 `docker compose logs --tail=120 app`
- 再看 `docker compose logs --tail=120 docreader`
- 再看 `docker compose logs --tail=120 mock-embedding`

## Step 10：用 SQL 确认 embedding 已写库

```bash
docker compose --env-file .env exec postgres psql -U agentic -d agentic_rag -c "select count(*) as embedding_count from embedding;"
```

如果你想再看最近写入的模型名和维度：

```bash
docker compose --env-file .env exec postgres psql -U agentic -d agentic_rag -c "select model_name, dimension, created_at from embedding order by id desc limit 5;"
```

预期：

- `embedding_count` 大于 `0`

## Step 11：常用清理命令

只停服务，不删卷：

```bash
docker compose --env-file .env down
```

停服务并删除卷：

```bash
docker compose --env-file .env down -v
```

## 常见问题

1. `docker compose up` 卡在拉镜像  
看 Docker 镜像加速是否还生效。
2. `app` 启动了但 ingest 失败  
优先看 `mock-embedding` 日志和 app 日志。
3. `embedding_count=0`  
说明任务没真正走到 embedding 写库，不是“只差 SQL 查询”。

## 阶段 2 完成标准

- `docker compose up -d --build` 可以拉起完整拓扑
- `app`、`docreader`、`mock-embedding` HTTP 检查通过
- `e2e_async_ingest_local.sh` 最终成功
- PostgreSQL `embedding` 表中有记录
