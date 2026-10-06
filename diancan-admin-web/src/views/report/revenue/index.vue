<script setup lang="ts">
import { computed, h, nextTick, onMounted, ref, watch } from 'vue';
import { NAlert, NButton, NCard, NDataTable, NDatePicker, NDrawer, NDrawerContent, NEmpty, NInput, NRadioButton, NRadioGroup, NSpin, NTabPane, NTabs, NTag, useMessage } from 'naive-ui';
import { useWindowSize } from '@vueuse/core';
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import AdjustmentCards from './adjustment-cards.vue';
import DailyReconciliation from './daily-reconciliation.vue';
import type { DataTableColumns } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import type { ECOption } from '@/hooks/common/echarts';
import { fetchRevenueDaily, fetchRevenueDetail, exportRevenue } from '@/service/api';
import { groupTrend, money, shanghaiToday, shiftDate, sumMoney, adjacentDate } from './ledger.mjs';

defineOptions({ name: 'ReportRevenue' });
const message = useMessage();
const today = shanghaiToday();
const days = ref<Api.Business.RevenueDaily[]>([]);
const loading = ref(false);
const loadError = ref('');
const exporting = ref(false);
const dimension = ref('day');
const dateRange = ref<[string, string]>([shiftDate(today, -6), today]);
const selectedDate = ref(today);
const detail = ref<Api.Business.RevenueDaily | null>(null);
const detailLoading = ref(false);
const detailError = ref('');
const keyword = ref('');
const selectedBill = ref<Api.Business.RevenueBill | null>(null);
const drawerVisible = ref(false);
const panelView = ref<'day' | 'bill'>('day');
const detailTab = ref('orders');
const dayPane = ref<HTMLElement | null>(null);
const billPane = ref<HTMLElement | null>(null);
const { width: windowWidth } = useWindowSize();
const panelWidth = computed(() => windowWidth.value < 768 ? windowWidth.value : Math.min(1120, Math.round(windowWidth.value * 0.88)));
const compactDetails = computed(() => panelWidth.value < 1000);
const previousDate = computed(() => adjacentDate(days.value, selectedDate.value, -1));
const nextDate = computed(() => adjacentDate(days.value, selectedDate.value, 1));
let dayTrigger: HTMLElement | null = null;
let billTrigger: HTMLElement | null = null;
let dayScrollTop = 0;
useMerchantOverlays(drawerVisible);
let loadSequence = 0;
let detailSequence = 0;
const totals = computed(() => ({
  net: sumMoney(days.value, 'totalRevenue'), received: sumMoney(days.value, 'receivedAmount'), refund: sumMoney(days.value, 'refundAmount'),
  orders: days.value.reduce((sum, d) => sum + d.orderCount, 0),
  average: days.value.length ? sumMoney(days.value, 'totalRevenue') / days.value.length : 0,
  wechat: sumMoney(days.value, 'wechatAmount'), alipay: sumMoney(days.value, 'alipayAmount'), cash: sumMoney(days.value, 'cashAmount'), other: sumMoney(days.value, 'otherAmount')
}));
const trend = computed(() => groupTrend(days.value, dimension.value));
const newestDays = computed(() => [...days.value].reverse());
const detailOrders = computed(() => (detail.value?.orders ?? []).filter(order => !keyword.value.trim() || `${order.orderNo} ${order.tableCode}`.toLowerCase().includes(keyword.value.trim().toLowerCase())));
type DailyItem = Api.Business.RevenueItem & { orderNo: string; tableCode: string; orderStatus: string };
const detailItems = computed<DailyItem[]>(() => (detail.value?.orders ?? []).flatMap(bill => bill.items.map(item => ({ ...item, id: `${bill.id}-${item.id}`, orderNo: bill.orderNo, tableCode: bill.tableCode, orderStatus: bill.status }))));
const detailDateRange = computed<[string, string]>(() => [dateRange.value[0], dateRange.value[1]]);
const rowKey = (row: { id: string }) => row.id;
const yuan = (value: unknown) => `¥${money(value)}`;
const time = (value: string | null | undefined) => value?.replace('T', ' ') || '—';
const tag = (status: string) => h(NTag, { size: 'small', bordered: false, type: status === '待结账' ? 'warning' : status === '已结账' ? 'success' : 'default' }, () => status);
function openBill(bill: Api.Business.RevenueBill, event?: MouseEvent) {
  billTrigger = event?.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  dayScrollTop = dayPane.value?.scrollTop || 0;
  selectedBill.value = bill; panelView.value = 'bill';
  nextTick(() => { if (billPane.value) billPane.value.scrollTop = 0; });
}
function returnToDay() {
  panelView.value = 'day';
  nextTick(() => { if (dayPane.value) dayPane.value.scrollTop = dayScrollTop; if (billTrigger?.isConnected) billTrigger.focus({ preventScroll: true }); });
}
function selectDay(date: string, event?: MouseEvent) {
  if (!days.value.some(day => day.date === date)) return;
  if (!drawerVisible.value) dayTrigger = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement as HTMLElement | null;
  selectedDate.value = date; keyword.value = ''; selectedBill.value = null; panelView.value = 'day';
  drawerVisible.value = true;
  nextTick(() => { if (dayPane.value) dayPane.value.scrollTop = 0; });
  loadDetail(date);
}
function restoreSummaryFocus() {
  panelView.value = 'day'; selectedBill.value = null;
  if (dayTrigger?.isConnected && dayTrigger.closest('.revenue-page')?.getClientRects().length) dayTrigger.focus({ preventScroll: true });
}
function isDateDisabled(timestamp: number) {
  const localDate = new Date(timestamp - new Date(timestamp).getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  return localDate < dateRange.value[0] || localDate > dateRange.value[1];
}
watch(drawerVisible, open => {
  if (!open) { ++detailSequence; detailLoading.value = false; }
});
const dailyColumns: DataTableColumns<Api.Business.RevenueDaily> = [
  { title: '日期', key: 'date', width: 122, fixed: 'left' },
  { title: '收款', key: 'receivedAmount', width: 115, align: 'right', render: r => yuan(r.receivedAmount) },
  { title: '退款', key: 'refundAmount', width: 105, align: 'right', render: r => yuan(r.refundAmount) },
  { title: '净营业额', key: 'totalRevenue', width: 120, align: 'right', render: r => h('strong', yuan(r.totalRevenue)) },
  { title: '收款订单', key: 'orderCount', width: 94, align: 'right' },
  { title: '微信净额', key: 'wechatAmount', width: 115, align: 'right', render: r => yuan(r.wechatAmount) },
  { title: '支付宝净额', key: 'alipayAmount', width: 115, align: 'right', render: r => yuan(r.alipayAmount) },
  { title: '现金净额', key: 'cashAmount', width: 115, align: 'right', render: r => yuan(r.cashAmount) },
  { title: '其他净额', key: 'otherAmount', width: 105, align: 'right', render: r => yuan(r.otherAmount) },
  { title: '操作', key: 'action', width: 116, fixed: 'right', render: r => h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: (event: MouseEvent) => selectDay(r.date, event) }, () => '查看当天') }
];
const billColumns: DataTableColumns<Api.Business.RevenueBill> = [
  { title: '桌号 / 订单', key: 'orderNo', width: 235, fixed: 'left', render: r => h('div', [h('strong', `${r.tableCode || '无桌号'}桌`), h('div', { class: 'muted order-number' }, r.orderNo)]) },
  { title: '开单时间', key: 'createTime', width: 174, render: r => time(r.createTime) },
  { title: '当前状态', key: 'status', width: 105, render: r => tag(r.status) },
  { title: '账单应收', key: 'actualAmount', width: 110, align: 'right', render: r => yuan(r.actualAmount) },
  { title: '当日收款', key: 'dayReceivedAmount', width: 110, align: 'right', render: r => yuan(r.dayReceivedAmount) },
  { title: '当日退款', key: 'dayRefundAmount', width: 110, align: 'right', render: r => yuan(r.dayRefundAmount) },
  { title: '当前待收', key: 'unsettledAmount', width: 110, align: 'right', render: r => yuan(r.unsettledAmount) },
  { title: '操作', key: 'action', width: 110, fixed: 'right', render: r => h(NButton, { size: 'small', onClick: (event: MouseEvent) => openBill(r, event) }, () => '看账单') }
];
const paymentColumns: DataTableColumns<Api.Business.RevenueReceipt> = [
  { title: '发生时间', key: 'time', width: 174, render: r => time(r.time) },
  { title: '桌号', key: 'tableCode', width: 80 },
  { title: '类型', key: 'kind', width: 80, render: r => h(NTag, { size: 'small', type: r.kind === '退款' ? 'warning' : 'success', bordered: false }, () => r.kind) },
  { title: '收款方式', key: 'paymentMethod', width: 130 },
  { title: '金额', key: 'amount', width: 115, align: 'right', render: r => `${r.kind === '退款' ? '−' : ''}${yuan(r.amount)}` },
  { title: '订单号', key: 'orderNo', width: 205 },
  { title: '收款流水号', key: 'paymentNo', width: 235 },
  { title: '退款原因', key: 'reason', width: 180, render: r => r.reason || '—' }
];
const adjustmentColumns: DataTableColumns<Api.Business.RevenueAdjustment> = [
  { title: '操作时间', key: 'time', width: 174, render: r => time(r.time) },
  { title: '桌号', key: 'tableCode', width: 80 },
  { title: '操作', key: 'kind', width: 120 },
  { title: '菜品 / 换菜', key: 'dishName', width: 180, render: r => r.description || r.dishName || '整单' },
  { title: '数量', key: 'quantity', width: 75, render: r => r.quantity ?? '—' },
  { title: '记录金额', key: 'amount', width: 110, align: 'right', render: r => r.amount === null ? '未记录' : yuan(r.amount) },
  { title: '原因', key: 'reason', width: 180, render: r => r.reason || '—' },
  { title: '操作人', key: 'operatorName', width: 100, render: r => r.operatorName || '未记录' },
  { title: '订单号', key: 'orderNo', width: 205 }
];
const itemColumns: DataTableColumns<Api.Business.RevenueItem> = [
  { title: '菜品', key: 'dishName', width: 150 },
  { title: '单价', key: 'price', width: 85, align: 'right', render: r => yuan(r.price) },
  { title: '数量', key: 'quantity', width: 65 },
  { title: '小计', key: 'amount', width: 90, align: 'right', render: r => yuan(r.amount) },
  { title: '计费状态', key: 'billingStatus', width: 110 },
  { title: '口味备注', key: 'remark', width: 150, render: r => r.remark || '—' },
  { title: '点菜时间', key: 'addedAt', width: 174, render: r => time(r.addedAt) }
];
const dailyItemColumns: DataTableColumns<DailyItem> = [
  { title: '桌号', key: 'tableCode', width: 80 },
  ...itemColumns,
  { title: '订单号', key: 'orderNo', width: 205 },
  { title: '账单状态', key: 'orderStatus', width: 100 }
];
// Use a separate HTML legend so labels can never fall onto the x-axis baseline.
const { domRef: chartRef, updateOptions } = useEcharts<ECOption>(() => ({
  tooltip: { trigger: 'axis', confine: true, valueFormatter: value => String(value) },
  legend: { show: false },
  grid: { left: 12, right: 12, top: 42, bottom: 16, containLabel: true },
  xAxis: { type: 'category', data: [], axisLabel: { hideOverlap: true, margin: 16 } },
  yAxis: [
    { type: 'value', name: '金额 / 元', nameTextStyle: { align: 'left' }, nameGap: 20, splitLine: { lineStyle: { color: 'rgba(128, 116, 105, 0.16)' } } },
    { type: 'value', name: '收款订单 / 笔', nameTextStyle: { align: 'right' }, min: 0, minInterval: 1, nameGap: 20, splitLine: { show: false } }
  ],
  series: [
    { name: '净营业额（元）', type: 'bar', barMaxWidth: 42, data: [], itemStyle: { color: '#b36742', borderRadius: [4, 4, 0, 0] } },
    { name: '收款订单（笔）', type: 'line', yAxisIndex: 1, data: [], smooth: false, symbolSize: 7, itemStyle: { color: '#567c72' }, lineStyle: { width: 2 } }
  ]
}), { onRender(instance) { instance.on('click', params => { if (dimension.value === 'day' && typeof params.name === 'string') selectDay(params.name); }); } });
function updateChart() {
  updateOptions(options => ({ ...options, xAxis: { type: 'category', data: trend.value.map(d => d.date), axisLabel: { hideOverlap: true, margin: 16 } },
    series: [
      { name: '净营业额（元）', type: 'bar', barMaxWidth: 42, data: trend.value.map(d => d.totalRevenue), itemStyle: { color: '#b36742', borderRadius: [4, 4, 0, 0] } },
      { name: '收款订单（笔）', type: 'line', yAxisIndex: 1, data: trend.value.map(d => d.orderCount), smooth: false, symbolSize: 7, itemStyle: { color: '#567c72' }, lineStyle: { width: 2 } }
    ] }));
}
watch(dimension, updateChart);
async function loadDetail(date: string) {
  const sequence = ++detailSequence; detailLoading.value = true; detailError.value = ''; detail.value = null;
  try {
    const { data, error } = await fetchRevenueDetail(date);
    if (sequence !== detailSequence) return;
    if (error || !data) detailError.value = '当天明细加载失败，请重试'; else detail.value = data;
  } catch { if (sequence === detailSequence) detailError.value = '当天明细加载失败，请重试'; }
  finally { if (sequence === detailSequence) detailLoading.value = false; }
}
async function loadData() {
  const sequence = ++loadSequence; loading.value = true; loadError.value = '';
  // Clear stale totals and drilldowns as soon as filters change.
  ++detailSequence; detail.value = null; detailLoading.value = false; detailError.value = ''; drawerVisible.value = false; detailTab.value = 'orders';
  days.value = []; updateChart();
  try {
    const { data, error } = await fetchRevenueDaily({ startDate: dateRange.value[0], endDate: dateRange.value[1] });
    if (sequence !== loadSequence) return;
    if (error || !data) { loadError.value = '统计加载失败，请重试或缩小日期范围（最多366天）'; return; }
    days.value = data; updateChart();
    const date = data.some(d => d.date === selectedDate.value) ? selectedDate.value : dateRange.value[1];
    selectedDate.value = date;
  } catch { if (sequence === loadSequence) loadError.value = '统计加载失败，请重试'; }
  finally { if (sequence === loadSequence) loading.value = false; }
}
function quickRange(kind: string) {
  const end = shanghaiToday();
  dateRange.value = kind === 'today' ? [end, end] : kind === 'yesterday' ? [shiftDate(end, -1), shiftDate(end, -1)] : kind === 'month' ? [`${end.slice(0, 7)}-01`, end] : [shiftDate(end, -6), end];
  selectedDate.value = dateRange.value[1]; loadData();
}
function changeRange(value: [string, string] | null) { if (value) { dateRange.value = value; selectedDate.value = value[1]; loadData(); } }
async function handleExport(singleDay = false) {
  exporting.value = true;
  try { await exportRevenue({ startDate: singleDay ? selectedDate.value : dateRange.value[0], endDate: singleDay ? selectedDate.value : dateRange.value[1] }); message.success('已导出完整营业明细'); }
  catch (error) { message.error(error instanceof Error ? error.message : '导出失败'); }
  finally { exporting.value = false; }
}
onMounted(loadData);
</script>

<template>
  <div class="revenue-page">
    <NCard :bordered="false" class="revenue-heading">
      <div class="heading-row">
        <div><h2>营业统计</h2><p>看每天收了多少，再查每桌账单和每道菜。</p></div>
        <div class="report-filters"><NButton :disabled="loading || !!loadError" @click="selectDay(dateRange[1]); detailTab = 'reconciliation'">日结核对</NButton><NButton type="primary" :loading="exporting" :disabled="loading || !!loadError" @click="handleExport()">导出完整明细</NButton></div>
      </div>
      <div class="report-filters">
        <div class="quick-dates">
          <NButton size="small" @click="quickRange('today')">今天</NButton><NButton size="small" @click="quickRange('yesterday')">昨天</NButton>
          <NButton size="small" @click="quickRange('week')">近7天</NButton><NButton size="small" @click="quickRange('month')">本月</NButton>
        </div>
        <NDatePicker :formatted-value="detailDateRange" value-format="yyyy-MM-dd" type="daterange" :clearable="false" class="date-filter" @update:formatted-value="changeRange" />
        <NButton :loading="loading" @click="loadData">刷新</NButton>
      </div>
      <p class="report-basis">北京时间 · 按实际收款日统计 · 净营业额 = 收款 − 退款 · 现金已扣找零</p>
    </NCard>
    <NAlert v-if="loadError" type="error" :title="loadError"><NButton size="small" @click="loadData">重新加载</NButton></NAlert>
    <NSpin :show="loading">
      <div class="metrics">
        <div class="metric metric-primary"><span>净营业额</span><strong>{{ yuan(totals.net) }}</strong><small>收款 {{ yuan(totals.received) }} · 退款 {{ yuan(totals.refund) }}</small></div>
        <div class="metric"><span>收款订单数</span><strong>{{ totals.orders }}<em>笔</em></strong><small>加菜共用账单，每天按订单去重</small></div>
        <div class="metric"><span>日均营业额</span><strong>{{ yuan(totals.average) }}</strong><small>按所选 {{ days.length }} 个自然日计算，含零营业日期</small></div>
      </div>
      <NCard :bordered="false" class="trend-card">
        <div class="section-head"><h3>营业趋势</h3><NRadioGroup v-model:value="dimension" size="small"><NRadioButton value="day">按日</NRadioButton><NRadioButton value="week">按周</NRadioButton><NRadioButton value="month">按月</NRadioButton></NRadioGroup></div>
        <div class="chart-legend"><span><i class="legend-bar" />净营业额（元）</span><span><i class="legend-line" />收款订单（笔）</span></div>
        <div ref="chartRef" class="revenue-chart" />
        <p class="muted chart-tip">按日时点击柱子或折线上的点，可查看当天明细。周、月只汇总所选日期范围。</p>
      </NCard>
      <NCard :bordered="false">
        <div class="section-head"><h3>每日汇总</h3><span class="muted">{{ dateRange[0] }} 至 {{ dateRange[1] }}</span></div>
        <div class="payment-summary"><span>微信净额 <strong>{{ yuan(totals.wechat) }}</strong></span><span>支付宝净额 <strong>{{ yuan(totals.alipay) }}</strong></span><span>现金净额 <strong>{{ yuan(totals.cash) }}</strong></span><span v-if="totals.other">其他净额 <strong>{{ yuan(totals.other) }}</strong></span></div>
        <div class="desktop-table"><NDataTable :columns="dailyColumns" :data="newestDays" :row-key="r => r.date" :scroll-x="1222" :pagination="{ pageSize: 10 }" /></div>
        <div class="mobile-days">
          <button v-for="day in newestDays" :key="day.date" class="day-row" :class="{ selected: selectedDate === day.date }" @click="selectDay(day.date, $event)">
            <div><strong>{{ day.date }}</strong><small>{{ day.orderCount }}笔收款 · 退款 {{ yuan(day.refundAmount) }}</small></div><div class="day-row__amount"><strong>{{ yuan(day.totalRevenue) }}</strong><small>查看当天 →</small></div>
          </button>
        </div>
      </NCard>
    </NSpin>
    <NDrawer v-model:show="drawerVisible" :width="panelWidth" class="revenue-detail-drawer" placement="right" @after-leave="restoreSummaryFocus">
      <NDrawerContent :native-scrollbar="true" :body-content-style="{ padding: '0', overflow: 'hidden' }" header-class="revenue-panel-header">
        <template #header>
          <div class="panel-heading">
            <div><small class="muted">{{ panelView === 'day' ? '每日经营明细' : selectedDate + ' · 当天账单' }}</small><h3>{{ panelView === 'day' ? selectedDate : `${selectedBill?.tableCode || '无桌号'}桌 · 账单明细` }}</h3></div>
            <div class="panel-heading-actions"><NButton v-if="panelView === 'bill'" @click="returnToDay">返回当天</NButton><NButton @click="drawerVisible = false">返回汇总</NButton></div>
          </div>
          <div v-if="panelView === 'day'" class="panel-toolbar">
            <div class="day-navigation"><NButton :disabled="!previousDate" @click="previousDate && selectDay(previousDate)">上一天</NButton><NDatePicker :formatted-value="selectedDate" type="date" value-format="yyyy-MM-dd" :clearable="false" :to="false" :is-date-disabled="isDateDisabled" class="day-picker" @update:formatted-value="date => date && selectDay(date)" /><NButton :disabled="!nextDate" @click="nextDate && selectDay(nextDate)">下一天</NButton></div>
            <NButton type="primary" :loading="exporting" :disabled="detailLoading || !detail" @click="handleExport(true)">导出当天明细</NButton>
          </div>
        </template>
        <section v-show="panelView === 'day'" ref="dayPane" class="detail-pane-scroll daily-detail" :aria-label="selectedDate + '当天明细'">
          <p class="muted detail-note">包含当天开单、收款或发生调整的订单，跨天账单也能查到。</p>
        <NAlert v-if="detailError" type="error" :title="detailError"><NButton size="small" @click="loadDetail(selectedDate)">重新加载</NButton></NAlert>
        <NSpin :show="detailLoading">
          <template v-if="detail">
            <div class="day-totals"><span>收款 <strong>{{ yuan(detail.receivedAmount) }}</strong></span><span>退款 <strong>{{ yuan(detail.refundAmount) }}</strong></span><span>净营业额 <strong>{{ yuan(detail.totalRevenue) }}</strong></span><span>收款订单 <strong>{{ detail.orderCount }}笔</strong></span></div>
            <NTabs v-model:value="detailTab" type="line" animated>
              <NTabPane name="orders" :tab="`订单明细 (${detail.orders.length})`">
                <NInput v-model:value="keyword" placeholder="搜索桌号或订单号" clearable class="bill-search" />
                <p class="muted detail-note">当日收款用于核对营业额；应收、待收及状态是账单当前状态。点“看账单”展开菜品和操作记录。</p>
                <div v-if="!compactDetails"><NDataTable :columns="billColumns" :data="detailOrders" :row-key="rowKey" :scroll-x="1050" :pagination="{ pageSize: 10 }" /></div>
                <div v-else class="detail-bills" :class="{ 'detail-bills--columns': panelWidth >= 720 }"><NEmpty v-if="!detailOrders.length" description="当天没有符合条件的订单" /><article v-for="bill in detailOrders" :key="bill.id" class="bill-card"><div class="section-head"><strong>{{ bill.tableCode || '无桌号' }}桌</strong><NTag size="small" :bordered="false">{{ bill.status }}</NTag></div><small class="muted order-number">{{ bill.orderNo }}</small><p v-if="bill.guestCount" class="muted">{{ bill.guestCount }}人 · {{ bill.tablewareQuantity }}套餐具 · 餐具费 {{ yuan(bill.tablewareAmount) }}</p><dl><div><dt>账单应收</dt><dd>{{ yuan(bill.actualAmount) }}</dd></div><div><dt>当日收款</dt><dd>{{ yuan(bill.dayReceivedAmount) }}</dd></div><div><dt>当日退款</dt><dd>{{ yuan(bill.dayRefundAmount) }}</dd></div><div><dt>当前待收</dt><dd>{{ yuan(bill.unsettledAmount) }}</dd></div></dl><div class="section-head"><small>{{ bill.paymentMethods || '未收款' }}</small><NButton size="small" @click="openBill(bill, $event)">看账单</NButton></div></article></div>
              </NTabPane>
              <NTabPane name="items" :tab="`菜品明细 (${detailItems.length})`"><p class="muted detail-note">当天关联订单中保留的菜品，包含待结账单及跨天收款单；小计未分摊整单折扣，不等同于当天实收。退掉或换掉的菜见退菜免单记录。</p><NDataTable v-if="!compactDetails" :columns="dailyItemColumns" :data="detailItems" :row-key="rowKey" :scroll-x="1209" :pagination="{ pageSize: 15 }" /><div v-else class="record-cards"><NEmpty v-if="!detailItems.length" description="当天没有关联菜品" /><article v-for="item in detailItems" :key="item.id" class="record-card"><div class="section-head"><strong>{{ item.tableCode }}桌 · {{ item.dishName }}</strong><strong>{{ yuan(item.amount) }}</strong></div><p>{{ item.quantity }}份 × {{ yuan(item.price) }} · {{ item.billingStatus }}</p><p v-if="item.remark">口味：{{ item.remark }}</p><small class="muted">{{ time(item.addedAt) }} · {{ item.orderStatus }}</small><div class="muted order-number">{{ item.orderNo }}</div></article></div></NTabPane>
              <NTabPane name="payments" :tab="`收款流水 (${detail.payments.length})`"><p class="muted detail-note">收款和退款分别保留，流水净额与当天净营业额一致。</p><NDataTable v-if="!compactDetails" :columns="paymentColumns" :data="detail.payments" :row-key="rowKey" :scroll-x="1300" :pagination="{ pageSize: 10 }" /><div v-else class="record-cards"><NEmpty v-if="!detail.payments.length" description="当天没有收款或退款流水" /><article v-for="payment in detail.payments" :key="payment.id" class="record-card"><div class="section-head"><strong>{{ payment.tableCode }}桌 · {{ payment.kind }}</strong><strong>{{ payment.kind === '退款' ? '−' : '' }}{{ yuan(payment.amount) }}</strong></div><p>{{ payment.paymentMethod }} · {{ time(payment.time) }}</p><p v-if="payment.reason">原因：{{ payment.reason }}</p><div class="muted order-number">订单 {{ payment.orderNo }}</div><div class="muted order-number">流水 {{ payment.paymentNo }}</div></article></div></NTabPane>
              <NTabPane name="adjustments" :tab="`退菜免单 (${detail.adjustments.length})`"><p class="muted detail-note">结账前退菜、免单已减少应收，不再从营业额重复扣减；换菜金额未记录时不推算。</p><NDataTable v-if="!compactDetails" :columns="adjustmentColumns" :data="detail.adjustments" :row-key="rowKey" :scroll-x="1324" :pagination="{ pageSize: 10 }" /><AdjustmentCards v-else :rows="detail.adjustments" /></NTabPane>
              <NTabPane name="reconciliation" tab="日结核对" display-directive="if"><DailyReconciliation :date="selectedDate" /></NTabPane>
            </NTabs>
          </template>
          <NEmpty v-else-if="!detailLoading && !detailError" description="选择上方日期查看当天明细" />
          <div v-else-if="detailLoading" class="detail-placeholder">正在读取当天账单…</div>
        </NSpin>

        </section>
        <section v-show="panelView === 'bill'" ref="billPane" class="detail-pane-scroll bill-detail" aria-label="账单明细">
          <template v-if="selectedBill">
        <p class="order-number muted">{{ selectedBill.orderNo }}</p><p class="muted">开单 {{ time(selectedBill.createTime) }} · {{ selectedBill.status }}</p>
        <p class="detail-note">{{ selectedDate }} 收款 {{ yuan(selectedBill.dayReceivedAmount) }} · 退款 {{ yuan(selectedBill.dayRefundAmount) }} · 净收款 {{ yuan(selectedBill.dayNetAmount) }}</p>
        <div class="bill-amounts"><div><span>保留菜品及餐具原价</span><strong>{{ yuan(selectedBill.originalAmount) }}</strong></div><div><span>优惠 / 免单</span><strong>{{ yuan(selectedBill.discountAmount) }}</strong></div><div><span>账单应收</span><strong>{{ yuan(selectedBill.actualAmount) }}</strong></div><div><span>当前待收</span><strong>{{ yuan(selectedBill.unsettledAmount) }}</strong></div></div>
        <p v-if="selectedBill.guestCount" class="detail-note">用餐 {{ selectedBill.guestCount }} 人 · 一次性餐具 {{ selectedBill.tablewareQuantity }} 套 × {{ yuan(selectedBill.tablewareUnitPrice) }} = {{ yuan(selectedBill.tablewareAmount) }}</p>
        <h3>菜品明细</h3><p class="muted detail-note">显示当前保留的菜品和加菜时间；小计未分摊整单折扣，退掉或换掉的菜见下方操作记录。</p>
        <NDataTable v-if="!compactDetails" :columns="itemColumns" :data="selectedBill.items" :row-key="rowKey" :scroll-x="824" /><div v-else class="record-cards"><NEmpty v-if="!selectedBill.items.length" description="本单没有保留菜品" /><article v-for="item in selectedBill.items" :key="item.id" class="record-card"><div class="section-head"><strong>{{ item.dishName }}</strong><strong>{{ yuan(item.amount) }}</strong></div><p>{{ item.quantity }}份 × {{ yuan(item.price) }} · {{ item.billingStatus }}</p><p v-if="item.remark">口味：{{ item.remark }}</p><small class="muted">点菜 {{ time(item.addedAt) }}</small></article></div>
        <p v-if="selectedBill.remark" class="detail-note">订单备注：{{ selectedBill.remark }}</p>
        <h3 class="drawer-section">账单调整记录</h3><NDataTable v-if="!compactDetails" :columns="adjustmentColumns" :data="selectedBill.adjustments" :row-key="rowKey" :scroll-x="1324" /><AdjustmentCards v-else :rows="selectedBill.adjustments" />

          </template>
        </section>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.revenue-page { --revenue-text: #332c27; --revenue-muted: #786f67; --revenue-surface: #fffdf9; display: flex; flex-direction: column; gap: 16px; min-width: 0; padding-bottom: 8px; font-variant-numeric: tabular-nums; }
.revenue-page h2, .revenue-page h3 { margin: 0; color: var(--revenue-text, #332c27); }
.revenue-page h2 { font-size: 25px; font-weight: 650; }
.revenue-page h3 { font-size: 17px; font-weight: 600; }
.heading-row, .section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.heading-row p { margin: 6px 0 0; color: var(--revenue-muted, #786f67); }
.report-filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 22px; }
.quick-dates { display: flex; flex-wrap: wrap; gap: 6px; }
.date-filter { width: 300px; max-width: 100%; }
.report-basis { margin: 14px 0 0; color: var(--revenue-muted, #786f67); font-size: 12px; line-height: 1.7; }
.metrics { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 14px; margin-bottom: 16px; }
.metric { display: flex; flex-direction: column; gap: 8px; padding: 20px 22px; border-radius: 14px; background: var(--revenue-surface, #fffdf9); border: 1px solid rgba(136, 105, 83, .13); }
.metric-primary { background: rgba(179, 103, 66, .09); }
.metric span, .metric small { color: var(--revenue-muted, #786f67); }
.metric strong { font-size: clamp(24px, 2.2vw, 32px); font-weight: 650; line-height: 1.2; color: var(--revenue-text, #332c27); }
.metric em { font-size: 14px; margin-left: 6px; font-style: normal; font-weight: 400; }
.metric small { font-size: 12px; line-height: 1.6; }
.trend-card { margin-bottom: 16px; }
.chart-legend { display: flex; gap: 24px; flex-wrap: wrap; margin: 20px 0 4px; font-size: 12px; color: var(--revenue-muted, #786f67); }
.chart-legend span { display: inline-flex; align-items: center; gap: 8px; }
.legend-bar { display: inline-block; width: 12px; height: 12px; border-radius: 3px; background: #b36742; }
.legend-line { display: inline-block; width: 20px; height: 2px; background: #567c72; }
.revenue-chart { height: 310px; width: 100%; min-width: 0; }
.muted { color: var(--revenue-muted, #786f67); font-size: 12px; line-height: 1.7; }
.chart-tip { margin: 6px 0 0; }
.payment-summary, .day-totals { display: flex; flex-wrap: wrap; gap: 12px 28px; padding: 16px 0; font-size: 13px; }
.payment-summary strong, .day-totals strong { margin-left: 8px; }
/* Each view owns its scroll position; the overview never moves when drilling down. */
.detail-pane-scroll { height:100%; overflow:auto; overscroll-behavior:contain; padding:20px 24px 32px; box-sizing:border-box; min-width:0; }
.panel-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.panel-heading h3 { margin:4px 0 0; font-size:20px; line-height:1.4; }
.panel-heading-actions { display:flex; gap:8px; flex-shrink:0; }
.panel-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap; margin-top:16px; }
.day-navigation { display:flex; align-items:center; gap:8px; min-width:0; }
.day-picker { width:158px; min-width:0; }
.detail-bills, .record-cards { display:grid; grid-template-columns:1fr; gap:12px; }
.detail-bills--columns { grid-template-columns:repeat(2,minmax(0,1fr)); }
.bill-card, .record-card { border:1px solid rgba(136,105,83,.16); border-radius:12px; padding:16px; min-width:0; }
.record-card p { margin:8px 0; font-size:13px; line-height:1.7; }
.bill-card dl { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin:14px 0; }
.bill-card dt { font-size:12px; color:var(--revenue-muted,#786f67); }
.bill-card dd { margin:4px 0 0; font-weight:600; }
.day-totals { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; border-bottom:1px solid rgba(136,105,83,.12); margin-bottom:12px; }
.day-totals span { display:flex; flex-direction:column; gap:6px; font-size:12px; }
.day-totals strong { margin-left:0; font-size:19px; font-variant-numeric:tabular-nums; }
.daily-detail h3, .bill-detail h3 { font-size:17px; margin:0; }
.bill-detail h3.drawer-section { margin-top:28px; }
.bill-search { max-width: 330px; margin: 8px 0; }
.detail-note { margin: 8px 0 14px; line-height: 1.7; }
.order-number { overflow-wrap: anywhere; }
.mobile-days { display: none; }
.detail-placeholder { height: 180px; padding-top: 60px; text-align: center; }
.bill-amounts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin: 20px 0 28px; }
.bill-amounts div { display: flex; flex-direction: column; gap: 6px; padding: 14px; border-radius: 10px; background: rgba(179, 103, 66, .07); }
.bill-amounts span { font-size: 12px; }
.bill-amounts strong { font-size: 21px; font-variant-numeric: tabular-nums; }
.drawer-section { margin-top: 28px; }
@media (max-width: 767px) {
  .revenue-page { gap: 12px; }
  .revenue-page :deep(.n-card__content) { padding: 16px; }
  .heading-row { align-items: flex-start; gap: 12px; }
  .heading-row p { font-size: 12px; }
  .report-filters { gap: 10px; margin-top: 16px; }
  .date-filter { width: 100%; }
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .metric-primary { grid-column: 1 / -1; }
  .metric { padding: 14px; }
  .metric strong { font-size: 25px; }
  .metric small { font-size: 11px; }
  .section-head { gap: 10px; }
  .section-head > .muted { width: 100%; }
  .chart-legend { gap: 16px; }
  .revenue-chart { height: 265px; }
  .desktop-table { display: none; }
  .mobile-days { display: flex; flex-direction: column; gap: 10px; }
  .day-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; width: 100%; padding: 14px 12px; border-radius: 10px; border: 1px solid rgba(136, 105, 83, .13); background: transparent; color: inherit; text-align: left; cursor: pointer; min-height: 68px; }
  .day-row.selected { border-color: #b36742; background: rgba(179, 103, 66, .06); }
  .day-row small { display: block; margin-top: 5px; font-size: 11px; color: var(--revenue-muted, #786f67); }
  .day-row__amount { text-align: right; }
  .day-row:focus-visible { outline: 2px solid #b36742; outline-offset: 2px; }
  .detail-pane-scroll { padding:16px 16px calc(24px + env(safe-area-inset-bottom)); }
  .panel-heading h3 { font-size:17px; }
  .panel-heading-actions { gap:6px; }
  .panel-heading-actions :deep(.n-button) { padding:0 10px; }
  .panel-toolbar, .day-navigation { width:100%; }
  .day-navigation { gap:6px; }
  .day-picker { flex:1; width:auto; }
  .panel-toolbar > :deep(.n-button) { width:100%; }
  .day-totals { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .bill-card { padding:14px; }
  .bill-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 14px 0; }
  .bill-card dt { font-size: 11px; color: var(--revenue-muted, #786f67); }
  .bill-card dd { margin: 4px 0 0; font-weight: 600; }
}
</style>
<style>
.revenue-detail-drawer.n-drawer { max-width:100vw; --revenue-text:#332c27; --revenue-muted:#786f67; font-variant-numeric:tabular-nums; }
.revenue-panel-header { padding:20px 24px !important; }
@media (max-width:767px) { .revenue-panel-header { padding:calc(16px + env(safe-area-inset-top)) 16px 16px !important; } }
html.dark .revenue-page { --revenue-text: #f0e8df; --revenue-muted: #b8b1aa; --revenue-surface: #171a21; }
html.dark .revenue-detail-drawer { --revenue-text:#f0e8df; --revenue-muted:#b8b1aa; }
</style>
