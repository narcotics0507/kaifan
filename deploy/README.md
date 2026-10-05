# GitHub Actions 自动发布

工作流：`.github/workflows/ci.yml`。

- Pull Request 执行检查，`main` 的成功构建自动发布。
- 发布使用 GitHub 的 `production` Environment，仅允许 `main`。
- 使用 GitHub 托管 Ubuntu runner 构建，不在 2GB 门店服务器编译。
- Actions 固定到已核对的完整 commit SHA；依赖更新需提交代码并经过相同检查。
- 检查通过后传送一个包含后端应用代码、两个网页、迁移 SQL 和变化依赖的小压缩包。服务器按 SHA256 缓存 Maven 依赖，逐项校验后重组完整 JAR，避免每次上传整个 124MB JAR。
- 服务端核对整体 SHA256、每个文件校验和、Git commit 和路径；拒绝软链接、路径穿越及超限压缩包。
- 同一分支发布串行；同一 commit 重试不重复发布。

## 已有服务器接入

这些脚本针对已运行的 `/apps/kaifan` Docker Compose 部署。服务器的 `config/private.env`、`config/wechat.env`、MySQL、Redis 和图片目录独立于 Git 仓库。

准备一对仅供该仓库发布的 Ed25519 密钥。将 **公钥** 和 `deploy/` 脚本目录交给服务器管理员，以 root 执行：

```sh
bash deploy/install-ci-account.sh /path/to/deploy-key.pub
```

脚本建立 `kaifan-deploy` 发布账号，公钥强制进入固定的发布入口。该账号不加入 Docker 组；不能通过这个密钥打开交互式 shell、端口转发或 SFTP。它只接受 `release <40位commit> <64位SHA256>` 和标准输入中的发布包，以及只读的 `dependency-cache` 校验和查询。`validate <commit> <SHA256>` 只校验并重组发布包，不改数据库或在用版本。

安装过程验证当前数据库包含已上线的桌次、轮播场景、H5 请求防重和厨房顺序表，然后记录当前四份迁移文件的校验和；不重新执行已应用的迁移。若服务器不是这个已升级状态，安装会停止，需要先核对结构。

`compose.example.yaml`、`application.yml`、`nginx.conf` 和 `logback.xml` 是无生产密码的配置参考。自动发布不会替换在用配置文件或重新初始化数据库。

## GitHub 配置

在仓库 `Settings → Environments → production` 中配置下列 Secrets，并限制可发布分支为 `main`：

| Secret | 含义 |
|---|---|
| `DEPLOY_HOST` | 服务器地址 |
| `DEPLOY_PORT` | SSH 端口 |
| `DEPLOY_USER` | `kaifan-deploy` |
| `DEPLOY_SSH_KEY` | 专用发布私钥，不使用管理员口令 |
| `DEPLOY_KNOWN_HOSTS` | 已核验的 SSH 主机公钥记录 |

仓库 Variables：`PUBLIC_ORIGIN` 为实际网站地址，`SUPPORT_PHONE` 为要公开显示的点餐帮助电话；后者仅供前端构建，未设置时隐藏联系方式。

发布凭据不用于 Pull Request。CI 的 MySQL/Redis 是临时隔离服务，示例数据库密码 `ci-test-only` 不是生产密码。

## 备份、迁移与回滚

每次新发布先获取与现有每日备份一致的锁，然后保存一份 SQL 快照到 `/apps/kaifan/backups/github/`。

数据库迁移按名称排序执行，成功后记录 SHA256 到 `config/github-migrations.json`。**不要修改已经应用的 SQL；新增一个新的日期编号文件。** 迁移失败停止发布；MySQL 的 DDL 不能保证事务回滚，数据恢复需用备份处理，不自动用旧数据覆盖当前库。

新版本进入 `releases/<UTC时间>-gh-<commit前12位>`，保留旧网页哈希资源供已打开页面继续使用。切换后只重建后端和网页容器；数据库、Redis 和 MinIO 容器保持运行。检查后端 API、商家版本文件和顾客版本文件；失败自动切回上一版代码。

成功记录位于 `config/github-deployment.json`，包含本次和上一版路径。手动回滚：

```sh
sudo bash deploy/rollback.sh 20261005T120000Z-gh-0123456789ab
```

示例版本名需要换成实际保存的版本。回滚代码不会还原数据库变更。

网页版本证据：`/build-version.json` 和 `/order/build-version.json`。GitHub Actions 页面也保留对应发布结果和发布包。

需要临时停止生产发布时，将仓库 Variable `AUTO_DEPLOY` 设为 `paused`。此时流水线仍完成构建、传输与发布包校验，但不切换生产。删除该变量后恢复 main 的自动发布。
