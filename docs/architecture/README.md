# 架构图（archify）

本目录使用 [archify](https://github.com/tt-a1i/archify) 生成的可视化架构图，每张图均为自包含 HTML 文件，带深色/浅色主题切换和 PNG/SVG 导出。

## 图表索引

| 图 | 模式 | 说明 |
|---|---|---|
| [system-architecture.html](./system-architecture.html) | architecture | 点餐系统全景：顾客 / 管理员 / 后厨 — 三端 + API + 数据层 |
| [order-lifecycle.html](./order-lifecycle.html) | lifecycle | 订单状态机：待支付 → 待制作 → 制作中 → 全部出餐 → 已支付（含已取消/已退款旁支） |
| [ordering-sequence.html](./ordering-sequence.html) | sequence | 扫码 → 下单 → 后厨播报：完整调用链 8 个参与者 |
| [kitchen-broadcast-workflow.html](./kitchen-broadcast-workflow.html) | workflow | 后厨播报与制作工作流：下单到全部出餐的泳道协作 |
| [order-dataflow.html](./order-dataflow.html) | dataflow | 订单数据流：来源 → 接入 → 处理 → 存储 → 消费 |

## 源文件

每个图的源 JSON 在 `json/` 目录：

- `json/system-architecture.architecture.json`
- `json/order-lifecycle.lifecycle.json`
- `json/ordering-sequence.sequence.json`
- `json/kitchen-broadcast-workflow.workflow.json`
- `json/order-dataflow.dataflow.json`

## 重新生成

```bash
# 在仓库根目录执行
node <archify-skill-path>/bin/archify.mjs render architecture \
  docs/architecture/json/system-architecture.architecture.json \
  docs/architecture/system-architecture.html

# 同样方式用于其他四种 diagram_type：lifecycle / sequence / workflow / dataflow
```

每张图带 `node <archify-skill-path>/bin/archify.mjs validate <type> <json>` 与 `check <html>` 用于校验布局与 SVG 完整性。

## 设计原则

- **一个故事一条主线**：架构图突出热路径（顾客 → 小程序 → API → Order/Kitchen → Redis → 后厨），其他关系通过 `cards` 摘要或简化边缘表达。
- **暗/亮主题自适应**：每张 HTML 自带 CSS 变量主题切换，双主题 SVG 导出跟随宿主 `prefers-color-scheme`。
- **本地化栈**：后端 Spring Boot 3 / MyBatis-Plus / Sa-Token；数据 MySQL + Redis + RocketMQ；客户端微信小程序 + Vue3 管理后台。

## 相关业务模块

- 后端 15 个业务模块：audit / banner / cart / coupon / dish / feedback / kitchen / member / mq / order / payment / print / report / review / system / table
- WebSocket 事件：NEW_ORDER · RUSH_ORDER · ITEM_COMPLETED · ALL_COMPLETED · SOLD_OUT · TABLE_STATUS