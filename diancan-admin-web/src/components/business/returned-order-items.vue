<script setup lang="ts">
import { ref } from 'vue';
import { NButton,NModal } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import { fetchReceiptPreview } from '@/service/api';
const app=useAppStore(),open=ref(false),text=ref(''),loading=ref(false);
const props=defineProps<{ items?: Api.Business.ReturnedItem[]; orderId?:Api.Business.IdType }>();
async function preview(){if(!props.orderId)return;open.value=true;loading.value=true;const {data}=await fetchReceiptPreview(props.orderId);if(data)text.value=data;loading.value=false;}
useMerchantOverlays(open);

</script>
<template>
  <section v-if="items?.length" class="returned-records" aria-label="已退菜记录">
    <div class="returned-head"><strong>已退菜 · 不收费</strong><NButton v-if="orderId" size="small" :loading="loading" @click="preview">查看最新结账单</NButton></div>
    <div v-for="item in items" :key="item.id" class="returned-record"><span>{{item.dishName}} × {{item.quantity}}<small>{{item.reason || '已退菜'}}</small></span><b>¥0.00</b></div>
  </section>
  <NModal v-model:show="open" preset="card" title="最新结账单预览" :trap-focus="!app.isMobile" style="width:520px"><p>实体打印机尚未接入，此处只预览账单。改单后请重新查看最新金额。</p><pre class="receipt-text">{{text||'正在读取…'}}</pre></NModal>
</template>
<style scoped>
.returned-head{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px}.receipt-text{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;font-size:.875rem;line-height:1.8}
.returned-records{margin:12px 0;padding:12px;border:1px dashed #d5b69f;border-radius:10px;background:#faf1e6;color:#796252;font-size:.875rem;line-height:1.6}.returned-record{display:flex;justify-content:space-between;gap:12px;margin-top:8px}.returned-record>span{overflow-wrap:anywhere;min-width:0}.returned-record small{display:block;font-size:.8125rem}.returned-record b{white-space:nowrap;font-variant-numeric:tabular-nums}
</style>
