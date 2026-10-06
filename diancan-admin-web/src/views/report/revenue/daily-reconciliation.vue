<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { NAlert, NButton, NEmpty, NInput, NInputNumber, NSpin, useMessage } from 'naive-ui';
import { fetchReconciliation, saveReconciliation, type ReconciliationChannel, type ReconciliationReview } from '@/service/api/report';
import { channels, difference, hasDifference, reconciliationCsv, validActual } from './reconciliation.mjs';
import { money } from './ledger.mjs';
const props = defineProps<{ date: string }>();
const state = ref<ReconciliationReview | null>(null), loading = ref(false), saving = ref(false), error = ref(''), reason = ref('');
const actual = ref<Record<ReconciliationChannel, number | null>>({ wechat: null, alipay: null, cash: null, other: null });
const formToken = ref(''), formRevision = ref(0), message = useMessage();
let sequence = 0, disposed = false, timer: ReturnType<typeof setInterval>;
const last = computed(() => state.value?.history[0]);
const visibleChannels = computed(() => channels.filter(c => c.key !== 'other' || Number(state.value?.system.other) !== 0 || Number(last.value?.actual.other || 0) !== 0));
const changed = computed(() => !!state.value && (state.value.token !== formToken.value || state.value.revision !== formRevision.value));
const complete = computed(() => channels.every((c: {key: ReconciliationChannel}) => validActual(actual.value[c.key])));
const mismatch = computed(() => !!state.value && complete.value && hasDifference(actual.value, state.value.system));
const canSave = computed(() => !!state.value && !error.value && !changed.value && complete.value && (!mismatch.value || !!reason.value.trim()));
const status = computed(() => !last.value ? '尚未核对' : state.value?.stale ? '流水有变化，需重新核对' : hasDifference(last.value.actual, last.value.system) ? '已核对，有差额' : '已核对，各渠道一致');
async function load(accept = false, initialize = false) {
  const id = ++sequence; loading.value = true;
  try {
    const { data, error: failure } = await fetchReconciliation(props.date);
    if (disposed || id !== sequence) return;
    if (failure || !data) { error.value = '日结账目读取失败，请重试'; return; }
    error.value = ''; state.value = data;
    if (initialize) { const saved = data.history[0]; channels.forEach((c: {key: ReconciliationChannel}) => { actual.value[c.key] = saved ? Number(saved.actual[c.key]) : c.key === 'other' && Number(data.system.other) === 0 ? 0 : null; }); reason.value = saved?.reason || ''; }
    if (accept || initialize) { formToken.value = data.token; formRevision.value = data.revision; }
  } catch { if (id === sequence) error.value = '日结账目读取失败，请重试'; }
  finally { if (id === sequence) loading.value = false; }
}
async function save() {
  if (!canSave.value || saving.value || !state.value) return;
  saving.value = true;
  try {
    const { data, error: failure } = await saveReconciliation({ date: props.date, revision: formRevision.value, token: formToken.value, reason: reason.value.trim(), wechat: actual.value.wechat!, alipay: actual.value.alipay!, cash: actual.value.cash!, other: actual.value.other! });
    if (!failure && data) { state.value = data; formToken.value = data.token; formRevision.value = data.revision; message.success('日结核对已保存'); }
    else { await load(); message.warning('保存未完成，请核对最新账目后重试'); }
  } finally { saving.value = false; }
}
function exportSaved() {
  if (!state.value?.history.length) return;
  const url = URL.createObjectURL(new Blob([reconciliationCsv(props.date, state.value.history)], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a'); a.href = url; a.download = `${props.date}-日结核对.csv`; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
watch(() => props.date, () => { state.value = null; actual.value = { wechat: null, alipay: null, cash: null, other: null }; reason.value = ''; void load(true, true); });
const visible = () => { if (!document.hidden && !saving.value) void load(); };
onMounted(() => { void load(true, true); timer = setInterval(visible, 30000); document.addEventListener('visibilitychange', visible); });
onUnmounted(() => { disposed = true; ++sequence; clearInterval(timer); document.removeEventListener('visibilitychange', visible); });
</script>
<template>
  <section class="daily-reconciliation" aria-label="日结核对">
    <div class="reconcile-head"><div><h3>{{ date }} 日结核对</h3><p>{{ status }}</p></div><NButton :loading="loading" :disabled="saving" @click="load(true)">重新加载账目</NButton></div>
    <NAlert type="info" :show-icon="false">实际净收款 = 当天实际到账 − 当天实际退给顾客的钱。现金填当日净收款，扣除找零和退款，不填钱箱余额。跨天收款按到账日核对。此处只登记核对结果。</NAlert>
    <NAlert v-if="error" type="error">{{ error }}</NAlert><NAlert v-if="changed" type="warning">账目或核对记录有变化。已填金额保留，请点“重新加载账目”，复核后再保存。</NAlert>
    <NSpin :show="loading && !state"><template v-if="state">
      <div class="reconcile-summary">系统收款 ¥{{ money(state.system.received) }} · 退款 ¥{{ money(state.system.refund) }} · 净收款 ¥{{ money(state.system.net) }}</div>
      <div class="channel-grid"><article v-for="channel in visibleChannels" :key="channel.key" class="channel-row">
        <label :for="'actual-'+channel.key"><strong>{{ channel.name }}</strong><span>系统净收款 ¥{{ money(state.system[channel.key as ReconciliationChannel]) }}</span></label>
        <NInputNumber :id="'actual-'+channel.key" v-model:value="actual[channel.key as ReconciliationChannel]" :update-value-on-input="true" :show-button="false" :disabled="saving" placeholder="填写实际净收款（最多2位小数）" :aria-label="channel.name+'实际净收款'" />
        <small v-if="actual[channel.key] != null && !validActual(actual[channel.key])" class="difference mismatch">请填写有效金额，最多两位小数</small>
        <span class="difference" :class="{ mismatch: difference(actual[channel.key as ReconciliationChannel], state.system[channel.key as ReconciliationChannel]) !== 0 && actual[channel.key as ReconciliationChannel] != null }">差额 {{ actual[channel.key as ReconciliationChannel] == null ? '待填写' : '¥'+money(difference(actual[channel.key as ReconciliationChannel], state.system[channel.key as ReconciliationChannel])) }}</span>
      </article></div>
      <label class="reason-label">差额原因 / 核对备注 <NInput v-model:value="reason" type="textarea" :maxlength="500" :disabled="saving" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="有任一渠道差额时必须填写；无差额可选填" /></label>
      <div class="reconcile-actions"><NButton type="primary" :loading="saving" :disabled="!canSave || loading" @click="save">保存日结核对</NButton><NButton :disabled="!state.history.length" @click="exportSaved">导出已保存核对</NButton></div>
      <p v-if="last" class="saved-by">最近保存：{{ last.operator }} · {{ last.savedAt.replace('T',' ') }} · 第 {{ last.revision }} 次</p>
      <details class="unsettled-list"><summary>当前未结账 {{ state.unsettled.length }} 单（不计入当天实收）</summary><NEmpty v-if="!state.unsettled.length" description="当前没有待收账单" /><p v-for="bill in state.unsettled" :key="bill.orderNo">{{ bill.tableCode }}桌 · ¥{{ money(bill.amount) }}<small>{{ bill.orderNo }}</small></p></details>
      <details v-if="state.history.length" class="reconcile-history"><summary>核对历史（显示最近100次）</summary><article v-for="record in state.history" :key="record.revision"><strong>第 {{ record.revision }} 次 · {{ record.operator }}</strong><small>{{ record.savedAt.replace('T',' ') }}</small><p v-for="c in channels" :key="c.key">{{ c.name }}：实际 ¥{{ money(record.actual[c.key as ReconciliationChannel]) }} / 系统 ¥{{ money(record.system[c.key as ReconciliationChannel]) }} / 差额 ¥{{ money(difference(record.actual[c.key as ReconciliationChannel],record.system[c.key as ReconciliationChannel])) }}</p><p v-if="record.reason">备注：{{ record.reason }}</p></article></details>
      <p class="saved-by">保存不清台、不停业。当天继续收款或发生退款后，需要重新核对；实际转账、退款仍在对应收款平台完成。</p>
    </template></NSpin>
  </section>
</template>
<style scoped>
.reconcile-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px}.reconcile-head h3{margin:0;font-size:18px}.reconcile-head p{margin:6px 0;color:#8c593d}.reconcile-summary{padding:16px 0;line-height:1.8}.channel-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.channel-row{padding:14px;border:1px solid #e7dcd2;border-radius:12px;display:grid;gap:10px}.channel-row label{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}.channel-row label span,.saved-by{color:#786b60;font-size:13px}.difference{color:#477b64}.difference.mismatch{color:#b14c24;font-weight:600}.reason-label{display:grid;gap:8px;margin-top:18px}.reconcile-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:16px}.unsettled-list,.reconcile-history{margin-top:18px;border-top:1px solid #e7dcd2;padding-top:14px}summary{cursor:pointer;min-height:40px;line-height:40px}.reconcile-history article{margin-top:12px;padding:12px;background:#f9f5ef;border-radius:10px}.reconcile-history p{margin:5px 0}.unsettled-list small,.reconcile-history small{display:block;color:#786b60;overflow-wrap:anywhere}.saved-by{line-height:1.8}@media(max-width:600px){.channel-grid{grid-template-columns:1fr}.reconcile-head{align-items:flex-start}.reconcile-actions :deep(button){min-height:44px}.channel-row :deep(input){font-size:16px}}
</style>
