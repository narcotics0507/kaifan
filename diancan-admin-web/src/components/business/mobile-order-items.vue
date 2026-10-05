<script setup lang="ts">
import { NTag } from 'naive-ui';
defineProps<{ items: Api.Business.OrderItem[] }>();
function money(value?: number) { return Number(value || 0).toFixed(2); }
</script>
<template>
  <div class="mobile-order-items">
    <article v-for="item in items" :key="item.id" class="mobile-item" :data-item-id="item.id">
      <div class="mobile-card-heading"><strong>{{ item.dishName }}</strong><strong>{{ item.isGift === 1 ? '赠送' : '¥' + money(item.amount) }}</strong></div>
      <div class="mobile-card-meta">¥{{ money(item.price) }} × {{ item.quantity }}份 <NTag size="small" :type="item.status === 3 ? 'error' : 'default'">{{ item.status === 3 ? '已退菜' : '已下单' }}</NTag></div>
      <p v-if="item.remark" class="mobile-card-note">备注：{{ item.remark }}</p>
      <div v-if="$slots.actions" class="mobile-card-actions"><slot name="actions" :item="item" /></div>
    </article>
  </div>
</template>
<style scoped>
.mobile-item { padding: 12px 0; border-bottom: 1px solid #e5e9ef; }
.mobile-item:last-child { border-bottom: 0; }
.mobile-card-heading { display: flex; justify-content: space-between; gap: 12px; font-size: 16px; }
.mobile-card-heading strong:first-child { min-width: 0; overflow-wrap: anywhere; }
.mobile-card-heading strong:last-child { white-space: nowrap; font-variant-numeric: tabular-nums; }
.mobile-card-meta { margin-top: 6px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.mobile-card-note { margin-top: 5px; overflow-wrap: anywhere; }
.mobile-card-actions { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
html.dark .mobile-item { border-color: #ffffff16; }
</style>
