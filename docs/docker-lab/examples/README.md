# Docker Lab Examples

这里放的是 Docker Lab 阶段 2-5 的样例模板。

这些文件的用途不是在仓库里直接运行，而是：

1. 同步仓库到远端
2. 把对应阶段模板复制到 `/home/s/agentic_lab/infra/<phase>`
3. 进入运行目录再执行 `docker compose`

原因是：

- 模板里的相对路径，都是按远端运行目录设计的
- 这样业务仓库和实验编排目录是分开的

阶段关系：

- `phase2` 引入单机闭环和 `mock-embedding`
- `phase3` 在 `phase2` 的概念基础上扩成双实例和 nginx
- `phase4` 在 `phase3` 的概念基础上把 Redis 升级成主从 + Sentinel
- `phase5` 在 `phase4` 的概念基础上把 PostgreSQL 升级成主从复制

注意：

- 每个阶段目录本身就是完整快照，不依赖前一个阶段目录里的文件
- 进入下一阶段前，先把上一阶段容器 `docker compose down`
