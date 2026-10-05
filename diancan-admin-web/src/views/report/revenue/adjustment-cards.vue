<script setup lang="ts">
import { NEmpty } from 'naive-ui';
import { money } from './ledger.mjs';
defineProps<{ rows: Api.Business.RevenueAdjustment[] }>();
</script>
<template>
  <div class="adjustment-cards">
    <NEmpty v-if="!rows.length" description="没有账单调整记录" />
    <article v-for="row in rows" :key="row.id" class="adjustment-card">
      <div class="adjustment-head"><strong>{{ row.tableCode }}桌 · {{ row.kind }}</strong><strong>{{ row.amount === null ? '金额未记录' : '¥' + money(row.amount) }}</strong></div>
      <p>{{ row.description || row.dishName || '整单' }}<span v-if="row.quantity !== null"> · {{ row.quantity }}{{ row.kind === '人数/餐具调整' ? '套' : '份' }}</span></p>
      <p>原因：{{ row.reason || '未记录' }}</p>
      <small>{{ row.time?.replace('T', ' ') }} · {{ row.operatorName || '操作人未记录' }}</small>
      <div class="adjustment-number">{{ row.orderNo }}</div>
    </article>
  </div>
</template>
<style scoped>
.adjustment-cards { display:flex; flex-direction:column; gap:12px; }
.adjustment-card { padding:16px; border:1px solid rgba(136,105,83,.16); border-radius:12px; min-width:0; }
.adjustment-head { display:flex; justify-content:space-between; align-items:baseline; gap:12px; flex-wrap:wrap; font-size:14px; }
.adjustment-card p { margin:8px 0; font-size:13px; line-height:1.7; }
.adjustment-card small, .adjustment-number { font-size:12px; line-height:1.7; color:var(--revenue-muted,#786f67); }
.adjustment-number { overflow-wrap:anywhere; }
</style>
