export function money(value) { return Number(value ?? 0).toFixed(2); }
export function sumMoney(rows, key) { return rows.reduce((sum, row) => sum + Math.round(Number(row[key] ?? 0) * 100), 0) / 100; }
export function shanghaiToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const part = type => parts.find(p => p.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
export function shiftDate(date, offset) { const d = new Date(`${date}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + offset); return d.toISOString().slice(0, 10); }
export function periodKey(date, dimension) {
  if (dimension === 'month') return date.slice(0, 7);
  if (dimension !== 'week') return date;
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 4 - (d.getUTCDay() || 7));
  const year = d.getUTCFullYear();
  const week = Math.ceil((((d - new Date(Date.UTC(year, 0, 1))) / 86400000) + 1) / 7);
  return `${year}-W${String(week).padStart(2, '0')}`;
}
export function groupTrend(days, dimension) {
  const groups = new Map();
  for (const day of days) {
    const key = periodKey(day.date, dimension);
    const group = groups.get(key) ?? { date: key, totalCents: 0, orderCount: 0 };
    group.totalCents += Math.round(Number(day.totalRevenue) * 100);
    group.orderCount += Number(day.orderCount);
    groups.set(key, group);
  }
  return [...groups.values()].map(group => ({ date: group.date, totalRevenue: group.totalCents / 100, orderCount: group.orderCount }));
}

/** Date navigation stays within the reviewed range, including zero-revenue days. */
export function adjacentDate(days, date, direction) {
  const dates = days.map(day => day.date).sort();
  const index = dates.indexOf(date);
  return index < 0 ? null : dates[index + direction] ?? null;
}
