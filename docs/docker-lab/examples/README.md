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
- `phase3` 复用 `phase2/mock-embedding`
- `phase4` 复用 `phase2/mock-embedding` 和 `phase3/nginx`
- `phase5` 复用 `phase2/mock-embedding`、`phase3/nginx` 和 `phase4/redis`

注意：

- 复用的是目录，不是同时把所有阶段容器都跑起来
- 进入下一阶段前，先把上一阶段容器 `docker compose down`
