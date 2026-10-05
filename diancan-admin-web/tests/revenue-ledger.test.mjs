import test from 'node:test';
import assert from 'node:assert/strict';
import { groupTrend, money, shanghaiToday, shiftDate, sumMoney } from '../src/views/report/revenue/ledger.mjs';

test('daily cash figures retain cents and include zero days in averages', () => {
  const rows = [{ date: '2026-10-01', totalRevenue: 0.1, orderCount: 1 }, { date: '2026-10-02', totalRevenue: 0.2, orderCount: 1 }, { date: '2026-10-03', totalRevenue: 0, orderCount: 0 }];
  assert.equal(sumMoney(rows, 'totalRevenue'), .3);
  assert.equal(money(sumMoney(rows, 'totalRevenue') / rows.length), '0.10');
  assert.deepEqual(groupTrend(rows, 'month'), [{ date: '2026-10', totalRevenue: .3, orderCount: 2 }]);
  assert.equal(groupTrend(rows, 'day').length, 3);
});
test('refund-only days retain negative revenue and do not create receipt orders', () => {
  const rows = [{ date: '2026-10-01', totalRevenue: 20.15, orderCount: 1 }, { date: '2026-10-02', totalRevenue: -5.1, orderCount: 0 }];
  assert.deepEqual(groupTrend(rows, 'week'), [{ date: '2026-W40', totalRevenue: 15.05, orderCount: 1 }]);
});
test('ISO weeks use the ISO week year across New Year', () => {
  assert.deepEqual(groupTrend([{ date: '2021-01-01', totalRevenue: 10, orderCount: 1 }], 'week'), [{ date: '2020-W53', totalRevenue: 10, orderCount: 1 }]);
});
test('date filters use Shanghai day and calendar arithmetic independent of client timezone', () => {
  assert.equal(shanghaiToday(new Date('2026-10-04T16:01:00Z')), '2026-10-05');
  assert.equal(shiftDate('2026-01-01', -1), '2025-12-31');
  assert.equal(shiftDate('2024-03-01', -1), '2024-02-29');
});
