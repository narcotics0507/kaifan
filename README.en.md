# Kaifan

[![CI and deployment](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml/badge.svg)](https://github.com/narcotics0507/kaifan/actions/workflows/ci.yml)
[![MIT License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

[中文说明](README.md)

A single-store dine-in ordering system for small restaurants. Customers order through a QR-linked H5 menu, the kitchen follows electronic tickets, and staff manage tables and payments from a phone or tablet.

The workflow is **order first, pay after dining, combine additions into the same table bill, and clear the table after full settlement**.

## Features

- Customer menu, search, quantity controls, dish and order notes, and table bills.
- Merchant table board, waiter ordering, menu management, and dish photo upload.
- Guest confirmation and a one-time tableware charge of ¥1 per set for each dining visit.
- Separate kitchen queue numbers for the initial order and each addition batch, with electronic tickets and voice alerts.
- Shortage returns, dish waivers, and recorded bill adjustments.
- Manually confirmed WeChat/Alipay collection-code payments and cash payments with change.
- Daily revenue based on receipt dates, detailed bill views, and a seven-sheet Excel export.
- Daily reconciliation by payment channel, discrepancy reasons, staff attribution, revision history, stale-data protection, and CSV export.

## Development and deployment

The backend uses Java 17 / Spring Boot 3. The merchant web app uses Vue 3 / TypeScript / Naive UI, and the customer H5 uses Vue / Vite. Storage uses MySQL, Redis, and MinIO.

Development requires Maven 3.9+, Node.js 24, pnpm 11.19.0, MySQL 8, and Redis 7. See the [Chinese README](README.md#本地开发) for database initialization, private configuration, and startup commands.

Pushing to `main` triggers the existing GitHub Actions workflow: tests, type checks, frontend builds, and automatic deployment. No manual release upload is needed. Pull requests run checks only. The deployment preserves persistent data and configuration, backs up the database before migration, and rolls back code if health checks fail.

See [deployment instructions](deploy/README.md) for server setup, credentials, migrations, and rollback. Build versions are available at `/build-version.json` and `/order/build-version.json`.

## Current limitations

- Online automatic payment is disabled. Staff confirm receipt of offline payments; refund records do not transfer money.
- Physical cloud printing is not integrated. Kitchen voice alerts require an open foreground page and on-device sound verification; continuous background or lock-screen playback is not guaranteed.
- The H5 app is the current customer entry point. Mini Program source is retained, without implying a completed public release.
- Domains, HTTPS, and device compatibility require deployment-specific configuration and acceptance.

Details: [revenue and reconciliation](docs/revenue-report.md), [kitchen reminders](docs/kitchen-reminders.md).

## Attribution and license

Based on [YHlorra/diancan-system](https://github.com/YHlorra/diancan-system), under the [MIT License](LICENSE). Original project and merchant UI license notices are preserved in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
