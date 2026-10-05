# 灶边有味 · Kaifan

[![CI and deployment](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml/badge.svg)](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml)
[![MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

面向农家乐、小菜馆的单店堂食扫码点餐系统。顾客通过 H5 网页点菜，前台用手机或平板维护菜单和结账，厨房按点单顺序查看电子小票。

## 来源与许可证

本项目基于 **[YHlorra/diancan-system](https://github.com/YHlorra/diancan-system)** 二次开发，参考基线提交为 [`3ab6ca9c`](https://github.com/YHlorra/diancan-system/commit/3ab6ca9c7a024e38516a2a343d99b6ab3e3060bd)。原项目提供 Spring Boot 后端、Vue 管理端和微信小程序端，原许可证声明的版权人为 Henfon。

管理端沿用 **[Soybean Admin](https://github.com/soybeanjs/soybean-admin)** 的结构和组件体系，保留 [`diancan-admin-web/LICENSE`](diancan-admin-web/LICENSE) 中 Soybean 的 MIT 版权文本。本仓库的原代码及本次改动采用 [MIT License](LICENSE)。详细说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

这是一份带来源说明的二次开发版本，未将上游项目标记为原创。公开仓库不包含在用服务器的账户密码、业务数据库备份或微信密钥。

## 当前功能

- 顾客 H5：扫码关联桌台、浏览菜单、数量调整、口味备注、提交点单、本桌账单。
- 商家网页：桌台看板、服务员点单、菜品和分类管理、手机拍照或从相册上传菜品照片。
- 首次成功下单才开台；同桌加菜合账；整桌账单结清后自动清台。
- 首次下单确认用餐人数，按1元/套计入一次性餐具费；同桌加菜不重复收取，前台可留痕调整，菜品免单不影响餐具费。
- 微信/支付宝门店收款码人工确认和现金收款，播报桌号、收款方式和实际收款金额。
- 厨房首单和每次加菜分别排新顺序号，电子小票和语音使用同一个编号。
- 缺菜退掉并扣减账单，做错菜可以将该道菜免单，保留审计记录。
- 营业统计按实际收款日展示每日汇总，可展开订单、菜品、收款退款和退菜免单；Excel 导出完整明细。统计口径见 [docs/revenue-report.md](docs/revenue-report.md)。
- 保留微信小程序源码，当前主要交付入口为 H5。

**当前边界：** 真实微信在线支付没有启用；云打印机对接仍待设备与平台凭据验收，电子小票不能代表实体出纸；域名备案、HTTPS 和真实手机兼容性需要部署者自行完成验证。

## 项目结构

| 目录 | 内容 |
|---|---|
| `diancan-admin` | Java 17 / Spring Boot 3 后端 |
| `diancan-admin-web` | Vue 3 / TypeScript / Naive UI 商家端 |
| `diancan-customer-web` | Vue / Vite 顾客 H5 |
| `diancan-miniapp` | 保留的微信小程序端 |
| `db` | 上游示例数据库及按版本升级的 SQL |
| `deploy` | 发布脚本、服务器模板和接入文档 |
| `.github/workflows` | GitHub Actions 检查、构建和自动发布 |

## 本地开发

需要 Java 17、Maven 3.9+、Node.js 24、pnpm 11.19.0、MySQL 8 和 Redis 7。

数据库示例 `db/diancan-system.sql` 来自上游模拟数据，不是实际门店数据库。开发库导入后按文件名顺序执行 `db/upgrade/*.sql`；已有字段的旧迁移不要重复执行。

后端连接参数通过环境变量或不提交 Git 的 `application-local.yml` 配置。默认开发和测试配置中出现的 `123456` 仅用于隔离的演示/测试资源；生产环境使用自己的凭据，门店数据和密钥保持在服务器配置目录。

```sh
cd diancan-admin-web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm exec vite --mode dev --host 127.0.0.1
```

顾客 H5 复用已安装的前端依赖：

```sh
ln -s ../diancan-admin-web/node_modules diancan-customer-web/node_modules
cd diancan-customer-web
../diancan-admin-web/node_modules/.bin/vite --host 127.0.0.1
```

后端启动时关闭当前未使用的优惠券 MQ 和真实支付能力。开发专用 `restaurant.auth.allow-local-fixture-login=true` 仅允许本机测试，不要在生产环境启用。

## 检查和发布

向 `main` 推送代码后，GitHub Actions 执行后端测试、商家类型检查、顾客视口/语音检查及两端构建，检查通过才向已接入的服务器发布。Pull Request 运行检查，不使用发布凭据。其他分支合入 main 后才会自动发布。

服务器保留 `/apps/kaifan` 下的数据库、Redis、图片和私密配置。发布前备份数据库，升级失败保留旧版；应用健康检查失败自动回滚代码。数据库迁移不做自动数据回滚，恢复数据需从备份处理。

生产接入、Secrets、数据库迁移规则和回滚方式见 **[deploy/README.md](deploy/README.md)**。仓库内没有内置可用的生产账号。

可以通过 `VITE_SUPPORT_PHONE` 设置顾客端的点餐帮助电话；未设置时不显示联系电话。公开示例不绑定私人号码。
