<script setup lang="ts">
import { computed, ref } from 'vue';
import { useResizeObserver } from '@vueuse/core';
import { useRoute } from 'vue-router';
import { useRouteStore } from '@/store/modules/route';
import { useAppStore } from '@/store/modules/app';
const appStore = useAppStore();
function navigate(event: MouseEvent) {
  if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) appStore.dismissMerchantOverlays();
}
const root = ref<HTMLElement | null>(null);
const emit = defineEmits<{ height: [value: number] }>();
useResizeObserver(root, () => { const height = Math.ceil(root.value?.getBoundingClientRect().height || 0); if (height >= 44 && height <= 140) emit('height', height); });
const route = useRoute();
const routeStore = useRouteStore();
const shortcuts = [
  { key: 'service_table-board', label: '桌台', path: '/service/table-board' },
  { key: 'service_place-order', label: '点单', path: '/service/place-order' },
  { key: 'order_list', label: '订单', path: '/order/list' },
  { key: 'dish_list', label: '菜品', path: '/dish/list' },
  { key: 'service_kitchen', label: '后厨', path: '/service/kitchen' }
];
function flatten(menus: App.Global.Menu[]): App.Global.Menu[] {
  return menus.flatMap(menu => [menu, ...flatten(menu.children || [])]);
}
const links = computed(() => {
  const menus = flatten(routeStore.menus);
  return shortcuts.filter(item => menus.some(menu => menu.key === item.key))
    .map(item => ({ ...item, icon: menus.find(menu => menu.key === item.key)?.icon }));
});
function active(path: string) {
  if (path === '/order/list') return route.path.startsWith('/order/') || route.path === '/service/order-ops';
  if (path === '/dish/list') return route.path.startsWith('/dish/');
  if (path === '/service/table-board') return route.path === path || route.path === '/service/checkout';
  return route.path === path;
}
</script>
<template>
  <Teleport to="body">
  <nav ref="root" v-if="links.length" class="merchant-mobile-nav" aria-label="手机常用操作">
    <RouterLink v-for="item in links" :key="item.key" :to="item.path" @click="navigate" :class="{ active: active(item.path) }" :aria-current="active(item.path) ? 'page' : undefined">
      <component :is="item.icon" v-if="item.icon" class="merchant-mobile-nav__icon" />
      <span>{{ item.label }}</span>
    </RouterLink>
  </nav>
  </Teleport>
</template>
<style scoped>
.merchant-mobile-nav { position: fixed; z-index: 4000; inset: auto 0 0; display: flex; padding: 7px 6px calc(7px + env(safe-area-inset-bottom)); border-top: 1px solid #dce3ed; background: #fff; box-shadow: 0 -3px 14px #182c4d0a; }
.merchant-mobile-nav a { flex: 1; min-width: 0; min-height: 48px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; border-radius: 10px; color: #526178; font-size:0.8125rem; font-weight: 600; }
.merchant-mobile-nav a.active { color: rgb(var(--primary-color)); background: rgba(var(--primary-color), .1); }
.merchant-mobile-nav a:focus-visible { outline: 2px solid currentColor; }
.merchant-mobile-nav__icon { font-size:1.25rem; }
html.dark .merchant-mobile-nav { background: #111926; border-color: #2b3545; }
html.dark .merchant-mobile-nav a { color: #b8c4d6; }
html.dark .merchant-mobile-nav a.active { color: #b8caff; background: rgba(var(--primary-color), .22); }
</style>
