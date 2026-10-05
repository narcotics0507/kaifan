<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NInput, NInputNumber, NModal, NSpace, useMessage } from 'naive-ui';
import { updateOrderTableware } from '@/service/api';
const props = withDefaults(defineProps<{ order: Api.Business.Order; editable?: boolean }>(), { editable: false });
const emit = defineEmits<{ updated: [] }>();
const message = useMessage();
const show = ref(false), busy = ref(false);
const guests = ref<number | null>(null), quantity = ref<number | null>(null), reason = ref('');
let requestId = '';
const amount = computed(() => (quantity.value || 0).toFixed(2));
const canEdit = computed(() => props.editable && (props.order.status === 0 || (props.order.status === 1 && !Number(props.order.actualAmount))) && !Number(props.order.paidAmount));
function open() { guests.value = props.order.guestCount || 1; quantity.value = props.order.tablewareQuantity || 0; reason.value = ''; requestId = globalThis.crypto?.randomUUID?.() || `tableware-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`; show.value = true; }
function changeGuests(value: number | null) { if (quantity.value === guests.value) quantity.value = value; guests.value = value; }
async function save() {
  if (!Number.isInteger(guests.value) || !Number.isInteger(quantity.value) || !reason.value.trim()) { message.warning('请填写用餐人数、餐具套数和原因'); return; }
  busy.value = true;
  try {
    const { error } = await updateOrderTableware(props.order.id, { guestCount: guests.value!, quantity: quantity.value!, reason: reason.value.trim(), requestId });
    if (!error) { show.value = false; message.success('人数与餐具费已更新'); emit('updated'); }
  } finally { busy.value = false; }
}
</script>
<template>
  <section v-if="order.guestCount || order.tablewareQuantity || canEdit" class="tableware-summary">
    <div><strong>一次性餐具</strong><span>{{ order.guestCount ? `本桌${order.guestCount}人` : '历史账单未记录人数' }} · {{ order.tablewareQuantity || 0 }}套 × ¥{{ Number(order.tablewareUnitPrice || 1).toFixed(2) }}</span></div>
    <strong>¥{{ Number(order.tablewareAmount || 0).toFixed(2) }}</strong>
    <NButton v-if="canEdit" size="small" @click="open">调整人数 / 餐具</NButton>
    <NModal v-model:show="show" preset="card" title="调整本桌人数与餐具" :mask-closable="!busy" :closable="!busy" class="tableware-edit-modal">
      <p>餐具每套1元，单独计费；调整只影响这次用餐。</p>
      <label>用餐人数<NInputNumber :value="guests" :min="1" :max="99" :precision="0" @update:value="changeGuests" /></label>
      <label>实际餐具套数<NInputNumber v-model:value="quantity" :min="0" :max="99" :precision="0" /></label>
      <label>调整原因<NInput v-model:value="reason" maxlength="150" placeholder="例如：后来增加一位客人" /></label>
      <p>餐具费 ¥{{ amount }}，原为 ¥{{ Number(order.tablewareAmount || 0).toFixed(2) }}。菜品金额保持原规则。</p>
      <NSpace justify="end"><NButton :disabled="busy" @click="show = false">取消</NButton><NButton type="primary" :loading="busy" @click="save">确认调整</NButton></NSpace>
    </NModal>
  </section>
</template>
<style scoped>
.tableware-summary { display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin:16px 0; padding:14px; border:1px solid rgba(169,77,36,.15); border-radius:12px; background:rgba(169,77,36,.05); font-variant-numeric:tabular-nums; }
.tableware-summary > div:first-child { flex:1; min-width:170px; }
.tableware-summary span { display:block; margin-top:4px; font-size:12px; opacity:.75; }
</style>
<style>
.tableware-edit-modal.n-card { width:420px; max-width:calc(100vw - 24px); }
.tableware-edit-modal label { display:block; margin:16px 0; }
.tableware-edit-modal label > div { margin-top:6px; }
</style>
