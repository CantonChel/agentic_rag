# 从 0 开始手动搭 Docker 实验环境

这份文档是给你自己一条一条敲命令用的，不走 bundle，不走一键脚本。

目标不是一次性把所有服务都堆起来，而是先把每一步看懂，再逐步升级到：

- 单机 Linux 基础观察
- Docker / Compose
- 单机 Compose 闭环
- 双实例应用 + Nginx
- Redis Sentinel
- PostgreSQL 主从

## 先说当前真实环境

这是我已经帮你远端确认过的事实，后面的命令都按这个环境写：

- SSH 地址：`ssh -p 12453 s@5.tcp.vip.cpolar.cn`
- 远端用户：`s`
- 家目录：`/home/s`
- 实验根目录：`/home/s/agentic_lab`
- 业务代码目录：`/home/s/agentic_lab/app`
- 实验编排目录：`/home/s/agentic_lab/infra`
- 当前机器还没有安装 Docker
- 当前机器上 `80` 和 `9200` 已经被占用，实验统一避开

建议后面统一使用这些高位端口：

- `31080`：Nginx 统一入口
- `38081`：app-1 直连调试口
- `38082`：app-2 直连调试口
- `39090`：docreader
- `38089`：mock-embedding
- `35432`：PostgreSQL 主库
- `36432`：PostgreSQL 从库
- `36379`：Redis 主
- `36380`：Redis 从
- `26379`、`26380`、`26381`：Redis Sentinel

## 阶段 0：先认识机器

这一阶段先不要想着部署。先确认你知道自己在哪、机器有什么、哪些口已经被占了。

### 1. 登录远端

```bash
ssh -p 12453 s@5.tcp.vip.cpolar.cn
```

- 作用：进入你的实验机。
- 成功标志：提示符变成类似 `s@s:~$`。
- 常见问题：`Permission denied` 多半是 SSH 公钥没配好；`Connection refused` 多半是 cpolar 地址变了。

### 2. 看自己当前在哪

```bash
pwd
```

- 作用：看当前目录。
- 成功标志：大概率输出 `/home/s`。
- 为什么先做这个：很多运维事故本质上是“在错误目录执行了正确命令”。

### 3. 确认当前用户

```bash
whoami
```

- 作用：确认当前登录用户。
- 成功标志：输出 `s`。
- 为什么重要：后面 `sudo`、文件权限、Docker 用户组都跟当前用户绑定。

### 4. 看机器名

```bash
hostname
```

- 作用：确认自己连的是哪台机器。
- 成功标志：当前这台机器输出是 `s`。
- 为什么重要：以后你有多台机器时，最容易混淆“我到底改的是哪台”。

### 5. 看实验目录

```bash
ls -la /home/s/agentic_lab
```

- 作用：确认实验目录是否已经存在。
- 成功标志：你应该能看到 `app`、`infra`、`logs`、`volumes` 这些目录。
- 你现在的真实情况：这个目录已经存在。

### 6. 看更细一点的目录结构

```bash
find /home/s/agentic_lab -maxdepth 2 -mindepth 1 -type d | sort
```

- 作用：用两层深度看目录树，不会刷太多屏。
- 成功标志：你应该能看到 `/home/s/agentic_lab/app` 和 `/home/s/agentic_lab/infra`。
- 为什么重要：后面我们约定业务代码只放 `app`，编排和实验配置只放 `infra`。

### 7. 看端口占用

```bash
ss -ltn
```

- 作用：看当前机器有哪些 TCP 监听端口。
- 成功标志：你会看到一列 `LISTEN`。
- 你现在的真实情况：`80` 和 `9200` 已经被占用了。
- 为什么重要：容器映射端口时，如果撞口，服务根本起不来。

### 8. 看内存

```bash
free -h
```

- 作用：看机器内存总量和剩余量。
- 成功标志：能看到 `total`、`used`、`free`、`available`。
- 为什么重要：Redis、PostgreSQL、多个应用实例一起起时，内存是第一瓶颈。

### 9. 看磁盘

```bash
df -h
```

- 作用：看磁盘空间。
- 成功标志：重点看根分区和家目录所在分区是否还有余量。
- 为什么重要：Docker 镜像、容器日志、数据库数据卷都吃磁盘。

### 10. 确认 sudo 是否可用

```bash
sudo -v
```

- 作用：预热 sudo 凭证。
- 成功标志：输入密码后无报错。
- 为什么重要：安装 Docker 一定要用 sudo。

### 11. 先确认 Docker 现在确实没装

```bash
command -v docker
```

```bash
systemctl status docker
```

- 作用：一个看命令路径，一个看服务状态。
- 你现在的真实情况：当前机器还没有 Docker，所以这一步大概率看不到路径，`systemctl` 也会提示服务不存在。
- 为什么重要：先确认基线，再开始装，不然你永远不知道自己是在修旧环境还是搭新环境。

## 阶段 1：安装 Docker，并理解四个核心对象

这一步先只做 Docker 基础，不碰 Redis、不碰 PostgreSQL 主从、不碰项目业务代码。

### 1. 更新 apt 索引

```bash
sudo apt-get update
```

- 作用：刷新软件源索引。
- 成功标志：最后没有红色报错。
- 常见问题：网络慢、国外源拉不下来，或者上次 apt 被异常中断。

### 2. 安装基础依赖

```bash
sudo apt-get install -y ca-certificates curl gnupg
```

- 作用：后面添加 Docker 官方源需要这些工具。
- 成功标志：安装结束后没有报错。

### 3. 创建 Docker keyrings 目录

```bash
sudo install -m 0755 -d /etc/apt/keyrings
```

- 作用：放 Docker 软件源签名 key。
- 成功标志：命令正常返回。
- 为什么这样做：这是 Ubuntu 官方推荐方式，比老的 `apt-key` 更规范。

### 4. 下载 Docker 官方 GPG key

```bash
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
```

- 作用：把 Docker 官方签名 key 存到本地。
- 成功标志：命令正常返回，没有权限或网络错误。
- 常见问题：网络问题导致 `curl` 失败；如果文件已存在且权限不对，可能写不进去。

### 5. 修正 key 文件权限

```bash
sudo chmod a+r /etc/apt/keyrings/docker.gpg
```

- 作用：让 apt 能正常读取这个 key。
- 成功标志：命令正常返回。

### 6. 添加 Docker 官方软件源

先检查系统里是否已经有 Docker 的 `.sources` 配置：

```bash
ls -l /etc/apt/sources.list.d/docker.sources
```

- 如果这条命令能看到文件，说明系统里已经有 Docker 源定义了。
- 这种情况下不要再额外创建 `docker.list`，不然很容易出现“同一仓库重复定义但 Signed-By 不一致”的冲突。

如果系统里没有 `docker.sources`，再执行下面这条命令创建 `docker.list`：

```bash
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
```

- 作用：把 Docker 的 apt 源写到系统配置里。
- 成功标志：命令正常返回。
- 为什么这一条看着长：因为它会自动识别你的 CPU 架构和 Ubuntu 版本代号，避免手填出错。

### 7. 再刷新一次 apt 索引

```bash
sudo apt-get update
```

- 作用：让系统识别刚加进去的 Docker 软件源。
- 成功标志：输出里能看到 `download.docker.com`。

### 8. 安装 Docker Engine 和 Compose 插件

```bash
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

- 作用：安装 Docker 引擎、CLI、容器运行时和 Compose。
- 成功标志：安装完成没有报错。
- 重点理解：
- `docker-ce` 是引擎。
- `docker-ce-cli` 是命令行。
- `containerd.io` 是底层容器运行时。
- `docker-compose-plugin` 让你能用 `docker compose`。

### 9. 让 Docker 服务开机自启并立刻启动

```bash
sudo systemctl enable docker
```

```bash
sudo systemctl start docker
```

```bash
systemctl status docker
```

- 作用：让 Docker 成为长期服务，而不是手动临时拉起。
- 成功标志：`status` 里看到 `active (running)`。
- 常见问题：如果状态不是 running，继续看 `journalctl -u docker`。

### 10. 让当前用户以后不用每次都 sudo

```bash
sudo usermod -aG docker s
```

- 作用：把用户 `s` 加入 Docker 用户组。
- 成功标志：命令正常返回。
- 注意：这一步做完必须退出 SSH 再重新登录，不然当前会话还拿不到新组权限。

### 11. 重新登录后做三个验收

```bash
docker version
```

```bash
docker compose version
```

```bash
docker run --rm hello-world
```

- 作用：分别验证引擎、Compose 插件和最小容器运行能力。
- 成功标志：`hello-world` 会输出一段欢迎文本后退出。
- 如果失败：
- `Cannot connect to the Docker daemon` 多半是 Docker 服务没启动，或者你还没重新登录。
- `permission denied` 多半是用户组没生效。

## 阶段 1.5：用四个最小实验理解 Docker

### A. image 和 container

```bash
docker run -d --name lab-nginx -p 31080:80 nginx:alpine
```

- 作用：用 `nginx:alpine` 镜像启动一个叫 `lab-nginx` 的容器，并把主机 `31080` 映射到容器 `80`。
- 你学到的概念：
- image 是“模板”。
- container 是“运行中的实例”。

```bash
docker ps
```

- 作用：看当前正在运行的容器。
- 成功标志：能看到 `lab-nginx`。

```bash
curl -I http://127.0.0.1:31080
```

- 作用：验证端口映射是否成功。
- 成功标志：返回 `HTTP/1.1 200 OK`。

```bash
docker logs lab-nginx
```

- 作用：看容器日志。
- 为什么重要：以后排障首先看的就是这里。

```bash
docker exec -it lab-nginx sh
```

- 作用：进入容器内部。
- 为什么重要：以后你经常要进去确认配置文件、环境变量、网络连通性。

```bash
docker rm -f lab-nginx
```

- 作用：删除刚才那个测试容器。
- 为什么重要：实验完要学会清理，不然容器越堆越乱。

### B. volume

```bash
docker volume create lab-data
```

- 作用：创建一个 Docker 数据卷。
- 你学到的概念：volume 是给容器做持久化存储的，不会因为容器删了就消失。

```bash
docker run --rm -v lab-data:/data alpine sh -c 'echo hello >/data/demo.txt && cat /data/demo.txt'
```

- 作用：往 volume 里写一个文件并立刻读出来。
- 成功标志：输出 `hello`。

```bash
docker run --rm -v lab-data:/data alpine cat /data/demo.txt
```

- 作用：验证上一个容器退出后，数据还在。
- 成功标志：依然输出 `hello`。

### C. network

```bash
docker network create lab-net
```

- 作用：创建一个自定义网络。
- 你学到的概念：network 让多个容器能通过服务名互相访问。

```bash
docker run -d --name net-a --network lab-net alpine sleep 1d
```

- 作用：启动一个长期存活的测试容器。

```bash
docker run --rm --network lab-net alpine ping -c 2 net-a
```

- 作用：从另一个容器里通过容器名访问 `net-a`。
- 成功标志：`2 packets transmitted, 2 packets received` 一类的输出。
- 为什么重要：以后 Compose 里服务之间就是靠这个互相访问的。

```bash
docker rm -f net-a
```

```bash
docker network rm lab-net
```

- 作用：清理测试网络和容器。

## 阶段 2：单机 Compose 最小闭环

这一阶段开始，才进入你的项目。但在你跑完阶段 1 之前，不要急着做。

这一阶段的目标是：

- 把编排文件统一放到 `/home/s/agentic_lab/infra`
- 让业务代码继续放在 `/home/s/agentic_lab/app`
- 先只跑单实例 app
- 先只接单 PostgreSQL、单 Redis
- 把外部第三方调用换成本地 mock

你现在先记住这几个目录动作：

```bash
mkdir -p /home/s/agentic_lab/infra
```

```bash
mkdir -p /home/s/agentic_lab/logs
```

```bash
mkdir -p /home/s/agentic_lab/volumes
```

```bash
find /home/s/agentic_lab -maxdepth 2 -mindepth 1 -type d | sort
```

- 作用：确保实验目录结构干净、固定。
- 为什么重要：以后你在面试里可以明确说，“业务代码目录和部署编排目录我是分开的”。

等你阶段 1 跑通后，这一阶段真正会用到的核心命令会是这些：

```bash
cd /home/s/agentic_lab/infra/phase2 && docker compose --env-file .env up -d
```

```bash
cd /home/s/agentic_lab/infra/phase2 && docker compose --env-file .env ps
```

```bash
cd /home/s/agentic_lab/infra/phase2 && docker compose --env-file .env logs -f app
```

```bash
cd /home/s/agentic_lab/infra/phase2 && docker compose --env-file .env exec postgres psql -U agentic -d agentic_rag
```

- 这些命令对应的新文档是 `phase2-single-compose-manual.md`。
- 阶段 2 开始，我们不再复用项目根目录的 `docker-compose.yml`，而是改成独立 `infra` 路线。

## 阶段 3：双实例应用 + Nginx

这一阶段的重点不是“更复杂”，而是第一次让你看见多实例到底会带来什么问题。

你后面会用到的命令大致会是：

```bash
cd /home/s/agentic_lab/infra/phase3 && docker compose --env-file .env up -d
```

```bash
cd /home/s/agentic_lab/infra/phase3 && docker compose --env-file .env ps
```

```bash
curl -I http://127.0.0.1:31080
```

```bash
cd /home/s/agentic_lab/infra/phase3 && docker compose --env-file .env logs -f nginx
```

- 这几条分别对应：起双实例、看状态、从统一入口打请求、看入口层日志。
- 到这一阶段你要开始建立一个概念：同一个用户的连续请求不一定会落到同一台实例。
- 对应的新文档是 `phase3-two-apps-nginx-manual.md`。

## 阶段 4：Redis Sentinel

这一阶段的目标不是 Redis Cluster，而是先搞懂“主从不等于自动高可用”。

后面会用到的命令大致会是：

```bash
cd /home/s/agentic_lab/infra/phase4 && docker compose --env-file .env up -d
```

```bash
cd /home/s/agentic_lab/infra/phase4 && docker compose --env-file .env ps
```

```bash
cd /home/s/agentic_lab/infra/phase4 && docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

```bash
cd /home/s/agentic_lab/infra/phase4 && docker compose --env-file .env stop redis-master
```

```bash
cd /home/s/agentic_lab/infra/phase4 && docker compose --env-file .env exec redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

- 这几条分别对应：起 Redis 主从 + Sentinel、看服务状态、查当前主节点、主动打挂主节点、再次确认新的主节点。
- 你需要记住：Redis 复制默认是异步的，所以 Sentinel 解决的是可用性，不是强一致。
- 对应的新文档是 `phase4-redis-sentinel-manual.md`。

## 阶段 5：PostgreSQL 主从

这一阶段第一版不要急着做读写分离，先学会观察复制。

后面会用到的命令大致会是：

```bash
cd /home/s/agentic_lab/infra/phase5 && docker compose --env-file .env up -d
```

```bash
cd /home/s/agentic_lab/infra/phase5 && docker compose --env-file .env ps
```

```bash
cd /home/s/agentic_lab/infra/phase5 && docker compose --env-file .env exec pg-primary psql -U agentic -d agentic_rag
```

```bash
cd /home/s/agentic_lab/infra/phase5 && docker compose --env-file .env exec pg-replica psql -U agentic -d agentic_rag
```

```bash
cd /home/s/agentic_lab/infra/phase5 && docker compose --env-file .env exec pg-primary psql -U agentic -d agentic_rag -c "select * from pg_stat_replication;"
```

- 这几条分别对应：起主从、看状态、连主库、连从库、在主库看复制状态。
- 你要重点理解：主从复制能提升可用性和读扩展，但会引入复制延迟和一致性问题。
- 对应的新文档是 `phase5-postgres-replica-manual.md`。

## 现在最建议你实际去做的顺序

今天先只做下面这些，不要贪多：

1. 登录远端。
2. 把阶段 0 的命令全部跑一遍。
3. 安装 Docker。
4. 跑通 `hello-world`。
5. 跑通 `nginx:alpine`、`volume`、`network` 三个小实验。

做到这里，你已经不是“完全没接触过运维 / Docker”的状态了。

等你把阶段 1 跑通，后面就直接按 `docs/docker-lab/` 里的阶段文档往下做：

1. `phase2-single-compose-manual.md`
2. `phase3-two-apps-nginx-manual.md`
3. `phase4-redis-sentinel-manual.md`
4. `phase5-postgres-replica-manual.md`
5. `phase6-failure-drills-manual.md`

后面的节奏保持不变：

- 你手动执行
- 我逐条解释
- 不给一键脚本
- 每一阶段都先讲“为什么”，再讲“怎么做”
