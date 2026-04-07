# 阶段 4：Redis 主从 + Sentinel（独立 infra 版）

## 目标

这一阶段只升级 Redis：

- `redis-master`
- `redis-replica-1`
- `redis-sentinel-1`
- `redis-sentinel-2`
- `redis-sentinel-3`

其余部分继续沿用前面的双 app + nginx 结构。

你这一阶段要搞懂的核心点是：

- 主从复制不等于高可用
- 自动切主靠 Sentinel
- Redis 复制默认是异步的

## 前置条件

进入阶段 4 前，保留这些目录不要删：

- `/home/s/agentic_lab/infra/phase2`
- `/home/s/agentic_lab/infra/phase3`

原因：

- 阶段 4 复用阶段 2 的 `mock-embedding`
- 阶段 4 复用阶段 3 的 `nginx.conf`

在启动阶段 4 之前，先把阶段 3 的容器停掉，但不要删阶段 2 和阶段 3 目录：

```bash
cd /home/s/agentic_lab/infra/phase3
docker compose --env-file .env down
```

## Step 1：复制阶段 4 模板

```bash
cd /home/s/agentic_lab/app
find docs/docker-lab/examples/phase4 -maxdepth 3 -type f | sort
```

```bash
if [ -d /home/s/agentic_lab/infra/phase4 ]; then mv /home/s/agentic_lab/infra/phase4 /home/s/agentic_lab/infra/phase4.bak.$(date +%Y%m%d-%H%M%S); fi
mkdir -p /home/s/agentic_lab/infra/phase4
cp -R /home/s/agentic_lab/app/docs/docker-lab/examples/phase4/. /home/s/agentic_lab/infra/phase4/
cd /home/s/agentic_lab/infra/phase4
cp .env.example .env
```

## Step 2：确认复用和 Sentinel 参数

```bash
rg -n "MOCK_EMBEDDING_BUILD_CONTEXT|NGINX_CONFIG_PATH|SPRING_REDIS_SENTINEL|REDIS_MASTER_HOST_PORT|REDIS_REPLICA_HOST_PORT|SENTINEL" .env
```

重点看：

- `MOCK_EMBEDDING_BUILD_CONTEXT=../phase2/mock-embedding`
- `NGINX_CONFIG_PATH=../phase3/nginx/nginx.conf`
- `SPRING_REDIS_SENTINEL_MASTER=mymaster`
- `SPRING_REDIS_SENTINEL_NODES=redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379`

## Step 3：启动阶段 4 拓扑

```bash
docker compose --env-file .env config >/tmp/docker-lab-phase4.config
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

预期：

- `redis-master`、`redis-replica-1` 是 `running`
- 3 个 Sentinel 都是 `running`
- `app-1`、`app-2`、`nginx` 也能正常启动

## Step 4：先确认主从关系

看主库角色：

```bash
docker compose --env-file .env exec redis-master redis-cli info replication
```

看从库角色：

```bash
docker compose --env-file .env exec redis-replica-1 redis-cli info replication
```

预期：

- 主库里能看到 `role:master`
- 从库里能看到 `role:slave` 或 `role:replica`

## Step 5：用 Sentinel 查询当前主节点

```bash
docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

预期：

- 返回当前主节点地址，一开始应该是 `redis-master`

## Step 6：检查 app 是否还能正常对外服务

```bash
curl -i http://127.0.0.1:31080/api/jobs/not-found
```

如果你想看 app 是否明确提到了 Sentinel：

```bash
docker compose --env-file .env logs --tail=150 app-1 app-2 | rg "sentinel|redis|localhost:6379|6379" || true
```

说明：

- 如果 app 启动后仍明显表现出只认固定单点 Redis，那就把这一阶段先当作 Redis 层高可用实验
- 不要强行在文档里把“Redis 自身切主成功”和“应用已经无缝恢复”混为一谈

## Step 7：做主库故障实验

先记住当前主：

```bash
docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

停掉主库：

```bash
docker compose --env-file .env stop redis-master
```

轮询 Sentinel：

```bash
for i in $(seq 1 10); do docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster; sleep 2; done
```

预期：

- 过几秒后，新的主节点会被选出来

再看原从库角色：

```bash
docker compose --env-file .env exec redis-replica-1 redis-cli info replication
```

如果它已经变成主：

- 说明 Sentinel 切主生效了

## Step 8：恢复原主库

```bash
docker compose --env-file .env start redis-master
docker compose --env-file .env ps
```

恢复后再看角色：

```bash
docker compose --env-file .env exec redis-master redis-cli info replication
docker compose --env-file .env exec redis-replica-1 redis-cli info replication
```

观察点：

- 被拉起的旧主不一定还是主
- 它更可能以从节点身份回来

## 阶段 4 你要带走的结论

1. Redis 主从提供的是复制，不是自动高可用。
2. Sentinel 才负责监控、投票和切主。
3. Redis 复制是异步的，所以不能把它当成强一致系统。

## 阶段 4 完成标准

- 能查到当前主节点
- 停掉 Redis 主后能看到新主被选出来
- 文档层面能清楚区分“Redis 切主成功”和“应用是否已接入 Sentinel”
