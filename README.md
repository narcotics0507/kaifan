# 灶边有味 · Kaifan

[![检查与自动发布](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml/badge.svg)](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml)
[![MIT License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

[English](README.en.md)

面向小菜馆、农家乐的单店堂食扫码点餐系统。顾客扫码点菜，厨房看电子单，前台用手机或平板收款结账，店主查看每日营业明细和日结核对记录。

采用**先吃后付、同桌加菜合账、人工确认收款、结清自动清台**的经营流程。

## 功能一览

| 使用场景 | 功能 |
| --- | --- |
| 顾客点餐 | 扫码关联桌台、浏览和搜索菜单、调整数量、填写口味及整单备注、确认下单、查看本桌账单 |
| 前台操作 | 桌台看板、服务员代点和加菜、菜品与分类管理、拍照或从相册上传菜品照片 |
| 人数与餐具 | 首次下单确认人数，按每套1元计入餐具费；同桌加菜不重复收取，前台可留痕调整实际套数 |
| 厨房接单 | 新单和每批加菜分别排号、电子小票、桌号与顺序号语音提醒、缺菜退菜、做错菜免单 |
| 收款结账 | 微信和支付宝门店收款码人工确认、现金收款与找零、结清后自动清台、收退款记录和操作留痕 |
| 营业统计 | 按实际收款日统计，查看每天的订单、菜品、收退款及调整记录，导出七张明细表的 Excel |
| 日结核对 | 填写各渠道实际净收款、计算逐项差额、记录原因与核对人、保留历史、导出核对记录 |

## 一桌客人的使用流程

1. **扫码选菜**：扫码关联桌台，浏览和加入购物车；此时不会占用桌台。
2. **确认下单**：首次下单确认人数和餐具费。订单成功保存后才开台，厨房收到本次点单。
3. **同桌加菜**：顾客继续扫码点菜，或由服务员代点。加菜并入同一桌次账单，厨房为本批加菜分配新的顺序号。
4. **用餐后结账**：前台核对实际到账金额，选择微信、支付宝或现金，确认收款。整桌结清后自动清台。
5. **下一批入座**：重新扫码进入新的桌次，人数、购物车和账单重新开始，旧账单保留供查询。

厨房顺序号按北京时间自然日从 `001` 开始；加菜按实际提交时间排队。餐具费独立列示，不进入厨房菜品小票，也不参与菜品折扣或免单。

## 常用入口

以下路径相对于部署后的站点地址：

| 入口 | 路径 | 说明 |
| --- | --- | --- |
| 顾客菜单 | `/order/` | 可浏览菜单，下单需关联有效桌台二维码 |
| 桌台二维码 | `/order/tables/` | 商家登录后查看和下载桌台点餐码 |
| 桌台看板 | `/service/table-board` | 查看桌台、代点及加菜 |
| 服务员点单 | `/service/place-order` | 浏览菜单、选桌、整批提交 |
| 厨房 | `/service/kitchen` | 查看电子单、开启声音和试播 |
| 结账 | `/service/checkout` | 核对金额、确认到账并结账 |
| 营业统计 | `/report/revenue` | 每日明细、Excel 导出及日结核对 |

商家入口按账号角色和权限开放。

## 营业统计与日结

营业统计按**北京时间的实际收款日**归集，净营业额为当日收款减当日退款。待结账金额不计入实收；结账前的退菜、折扣和免单不再重复扣减。点击“查看当天”直接打开明细面板，手机和平板均可切换日期、查看账单和导出。

在“营业统计 → 日结核对”填写微信、支付宝、现金的实际净收款；有其他渠道流水时显示其他渠道。任一渠道存在差额，都需填写原因。保存后若新增收款、退款或其他员工的核对记录，系统提示重新核对。

日结只记录核对结果，**不改收款流水、不清台、不停止营业**。营业明细导出为 `.xlsx`；日结历史导出为可在 Excel 中打开的 `.csv`。

详细口径见 [营业统计与日结说明](docs/revenue-report.md)。

## 技术与目录

后端使用 Java 17 / Spring Boot 3；商家端使用 Vue 3 / TypeScript / Naive UI；顾客端为 Vue / Vite H5。数据存储使用 MySQL、Redis，图片上传使用 MinIO。

| 目录 | 内容 |
| --- | --- |
| `diancan-admin` | 后端接口与业务服务 |
| `diancan-admin-web` | 商家管理端 |
| `diancan-customer-web` | 顾客扫码点餐 H5 |
| `diancan-miniapp` | 保留的微信小程序源码 |
| `db` | 数据库样例及升级 SQL |
| `docs` | 统计口径、厨房提醒及系统架构说明 |
| `deploy` | 服务器配置模板、发布与回滚脚本 |
| `.github/workflows` | GitHub Actions 检查和自动发布流程 |

## 本地开发

### 环境与配置

准备 Java 17、Maven 3.9+、Node.js 24、pnpm 11.19.0、MySQL 8 和 Redis 7；联调图片上传时还需 MinIO。

1. 创建独立开发数据库，导入 `db/diancan-system.sql` 样例，再按文件名顺序应用 `db/upgrade/` 中的升级文件。样例包含建表和重建表操作，仅用于初始化新库；已有数据库只应用尚未执行的升级文件。
2. 配置数据库连接、Redis 和后台账号。后端配置可使用环境变量，或创建不提交 Git 的 `diancan-admin/src/main/resources/application-local.yml`。
3. 启用顾客 H5 时，设置 `restaurant.h5.enabled=true`、至少32字符的随机 `restaurant.h5.signing-key`，以及本地入口 `restaurant.h5.public-origin=http://127.0.0.1:9530`。更换签名密钥会影响已发出的桌台链接。
4. 关闭当前不使用的 `coupon.grant.mq.enabled` 和 `wx.pay.enabled`。如暂不联调图片上传，可设置 `storage.minio.enabled=false`；需要上传时配置 MinIO 的地址、凭据和图片访问地址。

配置字段可参考 [服务器配置模板](deploy/application.yml)。数据库口令、签名密钥和其他私密配置不提交仓库；开发专用 `restaurant.auth.allow-local-fixture-login` 不用于生产环境。

### 启动后端

完成上述配置后，在独立终端运行：

```sh
cd diancan-admin
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.address=127.0.0.1"
```

后端接口地址为 `http://127.0.0.1:8080/api`。

### 启动商家端

在另一个终端运行：

```sh
cd diancan-admin-web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm exec vite --mode dev --host 127.0.0.1
```

商家端地址为 `http://127.0.0.1:9527`，开发配置连接本机后端。

### 启动顾客端

从仓库根目录运行，复用商家端已安装的依赖：

```sh
ln -s ../diancan-admin-web/node_modules diancan-customer-web/node_modules
cd diancan-customer-web
../diancan-admin-web/node_modules/.bin/vite --host 127.0.0.1
```

顾客端地址为 `http://127.0.0.1:9530/order/`。已有 `node_modules` 时跳过建立链接；实际点餐请从桌台二维码或完整桌台链接进入。

## 检查与自动发布

向 `main` 提交并推送代码后，**GitHub Actions 自动执行检查、构建和发版**，无需手工上传发布包。Pull Request 仅运行检查，其他分支合入 `main` 后才触发生产发布。

流水线检查后端测试、商家类型、前端业务测试及两端构建。发布时保留现有数据库、Redis、图片和私密配置；先备份数据库，再执行新增迁移，切换版本后检查后端接口和两端版本。健康检查失败时自动回退代码；数据库恢复单独处理。

这套流水线需先接入目标服务器并配置发布凭据，接入步骤、暂停发布、迁移规则和回滚方法见 [部署说明](deploy/README.md)。

发布后的版本信息可通过 `/build-version.json` 和 `/order/build-version.json` 查询。

## 当前限制

- 微信、支付宝使用门店收款码，由前台确认实际到账；在线自动扣款未启用，退款记录也不代替实际退款操作。
- 厨房已支持电子单和语音，实体云打印尚未接通。营业时保持厨房页在前台、屏幕亮着，并先试播；真实设备的可听性需现场确认，锁屏或后台持续播报不作保证。详见 [厨房提醒说明](docs/kitchen-reminders.md)。
- 当前使用入口为 H5，微信小程序源码保留，但不代表已完成正式小程序发布。
- 域名、HTTPS 和不同设备的兼容性需在实际部署环境完成配置与验收。

## 来源与许可

本项目基于 [YHlorra/diancan-system](https://github.com/YHlorra/diancan-system) 二次开发，采用 [MIT License](LICENSE)。原项目及管理端组件的版权与许可证声明保留，详情见 [第三方声明](THIRD_PARTY_NOTICES.md)。
