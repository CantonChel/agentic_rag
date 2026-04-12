# 阶段 3：双实例 app + Nginx（独立 infra 版）

## 目标

这一阶段在阶段 2 成功闭环的基础上，加入：

- `app-1`
- `app-2`
- `nginx`

重点不是“更多服务”，而是开始观察：

- 同一个入口如何把请求转给不同实例
- 为什么多实例下本地内存状态不可靠
- 为什么统一入口层很重要

## 前置条件

进入阶段 3 前，建议你已经真正跑通过阶段 2，因为这一阶段是沿着阶段 2 的单机闭环继续往上加的。

如果阶段 2 的容器还在运行，先停掉，避免端口冲突：

```bash
cd /home/s/agentic_lab/infra/phase2
docker compose --env-file .env down
```

## Step 1：复制阶段 3 模板

```bash
cd /home/s/agentic_lab/app
find docs/docker-lab/examples/phase3 -maxdepth 3 -type f | sort
```

```bash
if [ -d /home/s/agentic_lab/infra/phase3 ]; then mv /home/s/agentic_lab/infra/phase3 /home/s/agentic_lab/infra/phase3.bak.$(date +%Y%m%d-%H%M%S); fi
mkdir -p /home/s/agentic_lab/infra/phase3
cp -R /home/s/agentic_lab/app/docs/docker-lab/examples/phase3/. /home/s/agentic_lab/infra/phase3/
cd /home/s/agentic_lab/infra/phase3
cp .env.example .env
```

## Step 2：确认阶段 3 自己带齐了运行文件

```bash
rg -n "MOCK_EMBEDDING_BUILD_CONTEXT|NGINX_CONFIG_PATH|APP_1_HOST_PORT|APP_2_HOST_PORT|NGINX_HOST_PORT" .env
```

你会看到：

- `MOCK_EMBEDDING_BUILD_CONTEXT=./mock-embedding`
- `NGINX_CONFIG_PATH=./nginx/nginx.conf`

这表示：

- 阶段 3 目录自己带了一份 `mock-embedding`
- Nginx 配置由阶段 3 自己提供
- 你把 `phase3` 整个目录复制到远端后，它就可以单独运行

## Step 3：检查端口

```bash
ss -ltn | rg ':(31080|38081|38082|39090|38089|39000|39001|35432)\b' || true
```

如果冲突：

- 回到 `.env` 改宿主端口

## Step 4：启动阶段 3 拓扑

```bash
docker compose --env-file .env config >/tmp/docker-lab-phase3.config
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

预期：

- `app-1`、`app-2`、`nginx` 都是 `running`
- 其余基础依赖也正常

## Step 5：验证统一入口

直接打 nginx：

```bash
curl -i http://127.0.0.1:31080/api/jobs/not-found
```

重点看响应头：

- `X-Upstream-Server`

连续打几次：

```bash
for i in $(seq 1 6); do curl -sSI http://127.0.0.1:31080/api/jobs/not-found | rg '^X-Upstream-Server'; done
```

预期：

- 你会看到 upstream 在 `app-1:8081` 和 `app-2:8081` 间切换

## Step 6：直接访问两个实例做对照

```bash
curl -i http://127.0.0.1:38081/api/jobs/not-found
curl -i http://127.0.0.1:38082/api/jobs/not-found
```

作用：

- 确认两个实例各自都活着
- 和通过 nginx 的统一入口形成对照

## Step 7：看 nginx 和 app 日志

```bash
docker compose --env-file .env logs --tail=120 nginx
docker compose --env-file .env logs --tail=120 app-1
docker compose --env-file .env logs --tail=120 app-2
```

重点看：

- nginx 是否正常转发到 upstream
- nginx access log 里是否带出了 `upstream=app-1:8081` 或 `upstream=app-2:8081`
- 两个 app 是否都能正常启动

## Step 8：做一个实例故障实验

先停掉 `app-1`：

```bash
docker compose --env-file .env stop app-1
docker compose --env-file .env ps
```

再从统一入口访问：

```bash
for i in $(seq 1 4); do curl -sSI http://127.0.0.1:31080/api/jobs/not-found | rg '^X-Upstream-Server'; done
```

预期：

- 统一入口仍能返回响应
- upstream 只剩 `app-2`

恢复 `app-1`：

```bash
docker compose --env-file .env start app-1
docker compose --env-file .env ps
```

## 阶段 3 你要带走的结论

1. 两个实例并不共享本地内存状态。
2. 对外统一入口应该由 nginx 之类的反向代理承担。
3. 多实例下，回调、会话、缓存、一致性问题都会比单机更明显。

## 阶段 3 完成标准

- nginx 入口 `31080` 可用
- 能通过 `X-Upstream-Server` 看懂一次请求命中了哪个实例
- 停掉一个 app 后，统一入口仍能访问另一个实例
