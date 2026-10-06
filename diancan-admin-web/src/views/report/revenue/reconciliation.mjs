export const channels = [{ key: 'wechat', name: '微信' }, { key: 'alipay', name: '支付宝' }, { key: 'cash', name: '现金' }, { key: 'other', name: '其他' }];
export function validActual(value) { return value != null && typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 9999999999.99 && Number(value.toFixed(2)) === value; }
export function difference(actual, system) {
  return actual == null ? null : (Math.round(Number(actual) * 100) - Math.round(Number(system) * 100)) / 100;
}
export function hasDifference(actual, system) { return channels.some(c => difference(actual[c.key], system[c.key]) !== 0); }
export function reconciliationCsv(date, history) {
  const rows = [['日期', '核对版本', '保存时间', '核对人', '渠道', '系统净收款', '实际净收款', '差额', '差额原因']];
  for (const record of history) for (const channel of channels) rows.push([date, record.revision, record.savedAt, record.operator, channel.name, Number(record.system[channel.key]), Number(record.actual[channel.key]), difference(record.actual[channel.key], record.system[channel.key]), record.reason]);
  const cell = value => { let text = String(value ?? ''); if (typeof value !== 'number' && /^[=+\-@\t\r]/.test(text)) text = "'" + text; return '"' + text.replaceAll('"', '""') + '"'; };
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n');
}
