# Docker Lab Docs

这个目录专门放“从 0 到分布式实验拓扑”的学习式搭建文档。

这条线分成两部分：

- 阶段 0-1：先学 Linux 观察、Docker 安装和最小实验
- 阶段 2-6：切到独立 `infra` 路线，按阶段逐步搭单机闭环、双实例、Redis Sentinel、PostgreSQL 主从和故障演练

## 目录关系

你后面会同时接触两个目录：

- 仓库模板目录：`/home/s/agentic_lab/app/docs/docker-lab/examples`
- 远端运行目录：`/home/s/agentic_lab/infra`

原则是：

- 模板放在仓库里，方便追踪和回滚
- 真正运行时，把对应阶段模板复制到 `/home/s/agentic_lab/infra/<phase>`
- 不直接在 `docs/docker-lab/examples` 里运行 `docker compose`
- 进入下一阶段前，保留前一阶段目录，但把前一阶段容器停掉，避免端口冲突

## 阶段地图

1. 阶段 0-1：机器观察 + Docker 基础安装与最小实验  
   文档：`phase0-1-from-zero.md`
2. 阶段 2：单机 Compose 完整 ingest 闭环  
   文档：`phase2-single-compose-manual.md`
3. 阶段 3：双实例 app + Nginx 统一入口  
   文档：`phase3-two-apps-nginx-manual.md`
4. 阶段 4：Redis 主从 + Sentinel  
   文档：`phase4-redis-sentinel-manual.md`
5. 阶段 5：PostgreSQL 主从复制  
   文档：`phase5-postgres-replica-manual.md`
6. 阶段 6：故障演练  
   文档：`phase6-failure-drills-manual.md`

## 模板目录

模板放在：

- `examples/phase2`
- `examples/phase3`
- `examples/phase4`
- `examples/phase5`

它们的角色是：

- `compose.yml`：定义服务拓扑、端口、卷和依赖
- `.env.example`：列出该阶段环境变量
- 各服务配置目录：放 nginx、redis、postgres 这类服务自己的配置
- `mock-embedding`：从阶段 2 开始引入，后续阶段复用它的接口

## 当前学习顺序

建议严格按顺序走：

1. 先完成 `phase0-1-from-zero.md`
2. 再做 `phase2-single-compose-manual.md`
3. 阶段 2 真正跑通后再进入 3、4、5、6

后面的阶段会明确复用前一个阶段已经准备好的目录和经验，所以不要跳阶段。
