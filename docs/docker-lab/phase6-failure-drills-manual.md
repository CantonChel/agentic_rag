# 阶段 6：故障演练（独立 infra 版）

## 目标

阶段 6 不再新增新组件。  
它直接复用阶段 5 的运行拓扑，固定做 3 类故障演练：

1. 停掉一个 app 实例
2. 停掉 `redis-master`
3. 把 `mock-embedding` 切到 `slow/error/timeout`

你的目标不是“把服务弄挂”，而是学会：

- 故障前先看什么
- 故障后看哪些日志和状态
- 怎么判断系统是在恢复、重试，还是已经真正失败

## 前置条件

先保证阶段 5 正在运行：

```bash
cd /home/s/agentic_lab/infra/phase5
docker compose --env-file .env ps
```

你后面的所有命令，都默认在这个目录执行。

## 演练 1：停掉一个 app 实例

### 演练前先看

```bash
for i in $(seq 1 4); do curl -sSI http://127.0.0.1:31080/api/jobs/not-found | rg '^X-Upstream-Server'; done
```

作用：

- 先确认 nginx 当前能在两个 app 实例间分发流量

### 停实例

```bash
docker compose --env-file .env stop app-1
docker compose --env-file .env ps
```

### 再次访问统一入口

```bash
for i in $(seq 1 4); do curl -sSI http://127.0.0.1:31080/api/jobs/not-found | rg '^X-Upstream-Server'; done
```

预期：

- 请求仍有响应
- upstream 只剩 `app-2`

### 看日志

```bash
docker compose --env-file .env logs --tail=80 nginx
docker compose --env-file .env logs --tail=80 app-2
```

### 恢复

```bash
docker compose --env-file .env start app-1
docker compose --env-file .env ps
```

## 演练 2：停掉 Redis 主节点

### 演练前先看谁是主

```bash
docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

### 停掉主节点

```bash
docker compose --env-file .env stop redis-master
```

### 轮询 Sentinel

```bash
for i in $(seq 1 10); do docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster; sleep 2; done
```

### 再看新主角色

```bash
docker compose --env-file .env exec redis-replica-1 redis-cli info replication
```

### 看 app 侧表现

```bash
docker compose --env-file .env logs --tail=120 app-1 app-2 | rg "redis|Redis|sentinel|exception|error" || true
```

### 恢复旧主

```bash
docker compose --env-file .env start redis-master
docker compose --env-file .env ps
```

这个演练要你观察的重点是：

- Redis 层切主是不是成功
- 应用层是不是自动恢复
- 这两件事不要混为一谈

## 演练 3：mock-embedding 慢、错、超时

### 3.1 先看当前模式

```bash
curl -fsS http://127.0.0.1:38089/healthz
```

### 3.2 切到 slow 模式

```bash
curl -sS -X POST http://127.0.0.1:38089/admin/mode -H 'Content-Type: application/json' -d '{"mode":"slow","delayMs":3000}'
```

然后跑一次 ingest：

```bash
APP_BASE_URL=http://127.0.0.1:31080 \
DOCREADER_BASE_URL=http://127.0.0.1:39090 \
KB_ID=kb-phase6-slow \
bash /home/s/agentic_lab/app/scripts/e2e_async_ingest_local.sh
```

看日志：

```bash
docker compose --env-file .env logs --tail=120 mock-embedding
docker compose --env-file .env logs --tail=120 app-1 app-2
```

### 3.3 切到 error 模式

```bash
curl -sS -X POST http://127.0.0.1:38089/admin/mode -H 'Content-Type: application/json' -d '{"mode":"error"}'
```

再跑一次 ingest：

```bash
APP_BASE_URL=http://127.0.0.1:31080 \
DOCREADER_BASE_URL=http://127.0.0.1:39090 \
KB_ID=kb-phase6-error \
bash /home/s/agentic_lab/app/scripts/e2e_async_ingest_local.sh
```

说明：

- 这一步失败是预期现象
- 重点不是脚本返回码，而是日志和任务状态

### 3.4 切到 timeout 模式

```bash
curl -sS -X POST http://127.0.0.1:38089/admin/mode -H 'Content-Type: application/json' -d '{"mode":"timeout"}'
```

再跑一次 ingest：

```bash
APP_BASE_URL=http://127.0.0.1:31080 \
DOCREADER_BASE_URL=http://127.0.0.1:39090 \
KB_ID=kb-phase6-timeout \
bash /home/s/agentic_lab/app/scripts/e2e_async_ingest_local.sh
```

说明：

- 这一步大概率会超时或进入失败重试
- 也是预期现象

### 3.5 恢复正常模式

```bash
curl -sS -X POST http://127.0.0.1:38089/admin/mode -H 'Content-Type: application/json' -d '{"mode":"normal","delayMs":0}'
curl -fsS http://127.0.0.1:38089/healthz
```

## 演练后的固定复盘问题

每次做完一个故障实验，都回答这 4 个问题：

1. 我最先看的是哪个服务状态？
2. 我最先翻的是哪个日志？
3. 故障是停在依赖层、应用层，还是入口层？
4. 恢复之后，我怎么确认系统不是“表面活着”而是真能处理请求？

## 阶段 6 完成标准

- 你能独立完成 3 类故障演练
- 你知道每类故障优先看什么
- 你能把“现象、原因、观测点、恢复动作”说清楚
