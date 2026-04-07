# 阶段 5：PostgreSQL 主从复制（独立 infra 版）

## 目标

这一阶段把 PostgreSQL 从单节点升级到：

- `pg-primary`
- `pg-replica`

这一版仍然坚持一个原则：

- app 只连主库
- 从库先只用于观察复制状态和演示延迟

## 前置条件

进入阶段 5 前，建议你已经跑过阶段 4，因为这一阶段默认你已经理解双实例入口和 Redis Sentinel。

如果阶段 4 的容器还在运行，先停掉，避免端口冲突：

```bash
cd /home/s/agentic_lab/infra/phase4
docker compose --env-file .env down
```

## Step 1：复制阶段 5 模板

```bash
cd /home/s/agentic_lab/app
find docs/docker-lab/examples/phase5 -maxdepth 4 -type f | sort
```

```bash
if [ -d /home/s/agentic_lab/infra/phase5 ]; then mv /home/s/agentic_lab/infra/phase5 /home/s/agentic_lab/infra/phase5.bak.$(date +%Y%m%d-%H%M%S); fi
mkdir -p /home/s/agentic_lab/infra/phase5
cp -R /home/s/agentic_lab/app/docs/docker-lab/examples/phase5/. /home/s/agentic_lab/infra/phase5/
cd /home/s/agentic_lab/infra/phase5
cp .env.example .env
```

## Step 2：确认阶段 5 自己带齐了运行文件和主从参数

```bash
rg -n "MOCK_EMBEDDING_BUILD_CONTEXT|NGINX_CONFIG_PATH|REDIS_MASTER_CONFIG_PATH|PG_PRIMARY|PG_REPLICA|REPLICATION" .env
```

重点看：

- `MOCK_EMBEDDING_BUILD_CONTEXT=./mock-embedding`
- `NGINX_CONFIG_PATH=./nginx/nginx.conf`
- `REDIS_MASTER_CONFIG_PATH=./redis/redis-master.conf`
- `PG_PRIMARY_HOST_PORT=35432`
- `PG_REPLICA_HOST_PORT=36432`
- `REPLICATION_USER=replicator`

这表示：

- 阶段 5 目录自己带了 `mock-embedding`
- 阶段 5 目录自己带了 `nginx.conf`
- 阶段 5 目录自己带了 Redis 配置
- 阶段 5 目录自己带了 PostgreSQL 主从配置

## Step 3：启动阶段 5 拓扑

```bash
docker compose --env-file .env config >/tmp/docker-lab-phase5.config
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

说明：

- PostgreSQL 主从第一次初始化比前面阶段更慢
- 如果你之前跑坏过一次，最省心的清理方式通常是 `docker compose down -v` 后重新来

## Step 4：确认主从复制已建立

```bash
docker compose --env-file .env exec pg-primary psql -U agentic -d agentic_rag -c "select application_name, client_addr, state, sync_state from pg_stat_replication;"
```

预期：

- 至少有一条复制记录

如果这里为空：

- 先看 `docker compose logs --tail=200 pg-primary`
- 再看 `docker compose logs --tail=200 pg-replica`

## Step 5：做一张复制探针表

```bash
docker compose --env-file .env exec pg-primary psql -U agentic -d agentic_rag -c "create table if not exists lab_replication_probe(id serial primary key, note text, created_at timestamptz default now());"
```

写一条数据：

```bash
docker compose --env-file .env exec pg-primary psql -U agentic -d agentic_rag -c "insert into lab_replication_probe(note) values ('phase5-primary-write');"
```

立刻去从库读：

```bash
docker compose --env-file .env exec pg-replica psql -U agentic -d agentic_rag -c "select * from lab_replication_probe order by id desc limit 5;"
```

如果没看到：

```bash
sleep 2
docker compose --env-file .env exec pg-replica psql -U agentic -d agentic_rag -c "select * from lab_replication_probe order by id desc limit 5;"
```

这一组实验要你体会的是：

- 主从复制通常不是强一致
- 写后立刻读从，有机会读不到

## Step 6：确认 app 仍只连主库

直接访问统一入口：

```bash
curl -i http://127.0.0.1:31080/api/jobs/not-found
```

看 app 日志里数据库连接信息：

```bash
docker compose --env-file .env logs --tail=120 app-1 app-2 | rg "jdbc:postgresql|pg-primary|pg-replica" || true
```

预期：

- app 使用的是 `pg-primary`
- 这一阶段不做读写分离

## Step 7：如果你想做一次完整链路回归

```bash
APP_BASE_URL=http://127.0.0.1:31080 \
DOCREADER_BASE_URL=http://127.0.0.1:39090 \
KB_ID=kb-phase5 \
bash /home/s/agentic_lab/app/scripts/e2e_async_ingest_local.sh
```

这一步是可选的。  
它的意义是确认：在 PostgreSQL 主从加入后，你的 app 仍然可以完成主库写入链路。

## 阶段 5 你要带走的结论

1. PostgreSQL 主从复制提升的是可用性和读扩展能力。
2. 第一版不要急着让业务读从。
3. 写后读一致性是做数据库主从时必须主动讨论的问题。

## 阶段 5 完成标准

- `pg_stat_replication` 能看到从库
- 你能完成一次“写主读从”的实验
- 你能观察并解释复制延迟现象
- app 仍然只连主库且基本功能可用
