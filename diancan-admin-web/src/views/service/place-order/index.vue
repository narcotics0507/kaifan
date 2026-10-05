<script setup lang="ts">
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import { useAppStore } from '@/store/modules/app';
import { useResizeObserver } from '@vueuse/core';
import { computed, nextTick, onActivated, onMounted, onUnmounted, ref } from 'vue';
import {
  NCard, NSpace, NButton, NInput, NInputNumber, NSelect,
  NTag, NSpin, NEmpty, NModal, NList, NListItem, NThing, NImage,
  NCheckbox,
  NDivider, useMessage
} from 'naive-ui';
import type { SelectOption } from 'naive-ui';
import { useRoute } from 'vue-router';
import { connectWebSocket, subscribe } from '@/service/websocket';
import {
  fetchTableList, fetchDishCategoryList, fetchDishList, createAdminOrder, fetchOrderList, addOrderItems
} from '@/service/api';
import {
  countOfflineAdminOrders,
  enqueueOfflineAdminOrder,
  listOfflineAdminOrders,
  markOfflineAdminOrderRetry,
  removeOfflineAdminOrder
} from '@/utils/offline-order-queue';

const appStore = useAppStore();
const message = useMessage();
const route = useRoute();
const loading = ref(false);
const isOffline = ref(!window.navigator.onLine);
const syncingOfflineOrders = ref(false);
const pendingOfflineCount = ref(0);
const pageRoot = ref<HTMLElement | null>(null);
const pageHeight = ref(640);
const pageWidth = ref(1200);
const compactOrdering = computed(() => appStore.isMobile || pageWidth.value < 720);
function updatePageHeight() {
  const bounds = pageRoot.value?.getBoundingClientRect();
  const top = bounds?.top || 0;
  if (bounds?.width) pageWidth.value = bounds.width;
  const viewport = window.visualViewport?.height || window.innerHeight;
  const footer = appStore.isMobile ? Number.parseFloat(document.documentElement.style.getPropertyValue('--merchant-nav-height')) || 72 : 12;
  pageHeight.value = Math.max(180, Math.floor(viewport - top - footer - 12));
}
useResizeObserver(pageRoot, updatePageHeight);

// ==================== 桌台选择 ====================
const tables = ref<Api.Business.DiningTable[]>([]);
const selectedTableId = ref<number | null>(null);
const guestCount = ref<number | null>(null);

const tableOptions = computed<SelectOption[]>(() =>
  tables.value.map(t => ({
    label: `${t.areaName || '未分区'} · ${t.name}（${t.code}）- ${{ 0: '未开单', 1: '已开台', 2: '已结账', 3: '待清洁' }[t.status] || '未知'}`,
    value: t.id
  }))
);

const selectedTable = computed(() => tables.value.find(t => t.id === selectedTableId.value));
const selectedTableHasActiveOrder = computed(() => selectedTable.value?.status === 1);
const selectedTableIsPaid = computed(() => selectedTable.value?.status === 2);
const selectedTableIsToClean = computed(() => selectedTable.value?.status === 3);
const showTablePicker = ref(false);
const showTableSwitch = ref(false);
const showSubmitConfirm = ref(false);
const pendingDish = ref<Api.Business.Dish | null>(null);
const pendingTableId = ref<number | null>(null);
const tableSearch = ref('');
const availableTables = computed(() => tables.value
  .filter(table => table.status === 0 || table.status === 1)
  .filter(table => `${table.code} ${table.name} ${table.areaName || ''}`.toLowerCase().includes(tableSearch.value.trim().toLowerCase()))
  .sort((a, b) => a.status - b.status || a.code.localeCompare(b.code, 'zh-Hans-CN')));

function chooseTable(id: number) {
  const table = tables.value.find(item => item.id === id);
  if (!table || (table.status !== 0 && table.status !== 1)) {
    message.warning('这张桌暂时不能点菜，请刷新桌台后重选');
    return;
  }
  if (selectedTableId.value && selectedTableId.value !== id && cart.value.length) {
    pendingTableId.value = id;
    showTablePicker.value = false;
    showTableSwitch.value = true;
    return;
  }
  if (selectedTableId.value !== id) guestCount.value = null;
  selectedTableId.value = id;
  showTablePicker.value = false;
  if (pendingDish.value) {
    const dish = allDishes.value.find(item => String(item.id) === String(pendingDish.value?.id)) || pendingDish.value;
    pendingDish.value = null;
    addToCart(dish);
  }
}

function confirmTableSwitch() {
  if (!pendingTableId.value) return;
  const nextTable = tables.value.find(table => table.id === pendingTableId.value);
  if (!nextTable || (nextTable.status !== 0 && nextTable.status !== 1)) {
    message.warning('桌台状态已变化，请刷新后重选');
    return;
  }
  clearCart();
  preOrderMode.value = false;
  guestCount.value = null;
  selectedTableId.value = nextTable.id;
  pendingTableId.value = null;
  showTableSwitch.value = false;
}

function initSelectedTableFromRoute() {
  const tableId = Number(route.query.tableId || 0);
  if (!tableId) return;
  const targetTable = tables.value.find(t => t.id === tableId);
  if (targetTable) selectedTableId.value = targetTable.id;
}

// ==================== 菜品分类与列表 ====================
const categories = ref<Api.Business.DishCategory[]>([]);
const activeCategoryId = ref<Api.Business.IdType | null>(null);
const allDishes = ref<Api.Business.Dish[]>([]);
const dishLoading = ref(false);
const searchKeyword = ref('');
const pageInitialized = ref(false);

const categoryDishCountMap = computed(() => {
  const map = new Map<string, number>();
  allDishes.value.forEach(dish => {
    const key = String(dish.categoryId ?? '');
    map.set(key, (map.get(key) || 0) + 1);
  });
  return map;
});

const dishList = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  return allDishes.value.filter(dish => {
    const matchCategory = Boolean(keyword) || !activeCategoryId.value || String(dish.categoryId) === String(activeCategoryId.value);
    if (!matchCategory) return false;
    if (!keyword) return true;
    const searchText = [
      dish.name,
      dish.categoryName,
      dish.description,
      dish.ingredients
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return searchText.includes(keyword);
  });
});

/** 首屏加载全部菜品，后续分类与搜索统一走前端过滤 */
async function loadAllDishes(showSectionLoading = false) {
  if (showSectionLoading) {
    dishLoading.value = true;
  }
  try {
    const params: Api.Business.DishQuery & { pageNum: number; pageSize: number } = {
      pageNum: 1,
      pageSize: 500,
      status: 1,
      orderableOnly: true
    };
    const { data, error } = await fetchDishList(params);
    if (!error && data) allDishes.value = data.list || [];
  } finally {
    if (showSectionLoading) {
      dishLoading.value = false;
    }
  }
}

/** 切换分类 */
function selectCategory(id: Api.Business.IdType | null) {
  activeCategoryId.value = id;
  searchKeyword.value = '';
  pageRoot.value?.querySelector('.waiter-menu-scroll')?.scrollTo({ top: 0 });
}

function handleSearch(val: string) {
  searchKeyword.value = val;
}

// ==================== 购物车（本地） ====================
interface CartItem {
  dishId: Api.Business.IdType;
  dishName: string;
  price: number;
  quantity: number;
  remark: string;
}

const cart = ref<CartItem[]>([]);
const cartTotal = computed(() => cart.value.reduce((sum, item) => sum + item.price * item.quantity, 0));
const cartCount = computed(() => cart.value.reduce((sum, item) => sum + item.quantity, 0));
const cartTotalText = computed(() => cartTotal.value.toFixed(2));
const cartDishCountMap = computed(() => {
  const map = new Map<string, number>();
  cart.value.forEach(item => {
    map.set(String(item.dishId), item.quantity);
  });
  return map;
});

/** 添加菜品到购物车 */
function addToCart(dish: Api.Business.Dish) {
  if (submitting.value) return;
  if (dish.soldOut === 1) {
    message.warning('这道菜卖完了');
    return;
  }
  if (!selectedTableId.value) {
    pendingDish.value = dish;
    tableSearch.value = '';
    showTablePicker.value = true;
    return;
  }
  if (selectedTableIsPaid.value || selectedTableIsToClean.value) {
    message.warning('这张桌已结账或待清洁，请重新选桌台');
    return;
  }
  const existing = cart.value.find(c => c.dishId === dish.id);
  if (existing) {
    existing.quantity++;
  } else {
    cart.value.push({ dishId: dish.id, dishName: dish.name, price: dish.price, quantity: 1, remark: '' });
  }
}

/** 修改数量 */
function updateQuantity(dishId: Api.Business.IdType, qty: number) {
  if (qty <= 0) {
    cart.value = cart.value.filter(c => c.dishId !== dishId);
  } else {
    const item = cart.value.find(c => c.dishId === dishId);
    if (item) item.quantity = qty;
  }
}

/** 清空购物车 */
function clearCart() { cart.value = []; }

// ==================== 提交订单 ====================
const submitting = ref(false);
const showCartModal = ref(false);
const preOrderMode = ref(false);
function requestSubmit() {
  if (submitting.value || !cart.value.length) return;
  if (!selectedTable.value) {
    tableSearch.value = '';
    showTablePicker.value = true;
    return;
  }
  showSubmitConfirm.value = true;
}
function generateClientOrderNo() {
  return `OFF${Date.now()}${Math.floor(Math.random() * 9000 + 1000)}`;
}

function isNetworkLikeError(error: unknown) {
  if (!window.navigator.onLine) return true;
  const msg = String(error ?? '').toLowerCase();
  return msg.includes('network') || msg.includes('timeout') || msg.includes('failed to fetch');
}

async function refreshPendingOfflineCount() {
  pendingOfflineCount.value = await countOfflineAdminOrders();
}

async function saveOrderToOfflineQueue(payload: Api.Business.AdminOrderCreate, clientOrderNo: string) {
  await enqueueOfflineAdminOrder(payload, clientOrderNo);
  await refreshPendingOfflineCount();
}

async function syncOfflineOrders(showToast = false) {
  if (syncingOfflineOrders.value || isOffline.value) return;
  syncingOfflineOrders.value = true;
  try {
    const pendingOrders = await listOfflineAdminOrders();
    if (!pendingOrders.length) return;

    let successCount = 0;
    for (const record of pendingOrders) {
      const { error } = await createAdminOrder(record.payload);
      if (!error) {
        await removeOfflineAdminOrder(record.id!);
        successCount += 1;
        continue;
      }

      if (isNetworkLikeError(error)) {
        await markOfflineAdminOrderRetry(record.id!, String(error));
        break;
      }

      await markOfflineAdminOrderRetry(record.id!, String(error));
    }

    await refreshPendingOfflineCount();
    if (showToast && successCount > 0) {
      message.success(`已自动补传 ${successCount} 笔离线订单`);
    }
  } finally {
    syncingOfflineOrders.value = false;
  }
}

function handleOnline() {
  isOffline.value = false;
  syncOfflineOrders(true);
}

function handleOffline() {
  isOffline.value = true;
}

const additionAttempts = new Map<string, { fingerprint: string; requestId: string }>();
async function submitAddition() {
  const table = selectedTable.value;
  if (!table || !cart.value.length) return;
  if (isOffline.value) { message.warning('加菜需要联网确认原账单，所选菜品已保留，请联网后重试'); return; }
  const items = cart.value.map(c => ({ dishId: c.dishId, quantity: c.quantity, remark: c.remark || undefined }));
  const session = table.currentSessionCode;
  if (!session) { message.warning('桌次信息未更新，请刷新桌台后重试'); await refreshOrderingMenu(); return; }
  submitting.value = true;
  try {
    const { data, error } = await fetchOrderList({ tableId: table.id, status: 0, pageNum: 1, pageSize: 200 });
    if (error || !data) return;
    const order = data.list.find(o => o.status === 0 && o.tableSessionCode === session);
    if (!order) { message.warning('本桌没有可加菜的待结账订单，请刷新桌台后核对'); await refreshOrderingMenu(); return; }
    const key = `kaifan.waiter.add.${table.id}`;
    const fingerprint = JSON.stringify({ orderId: order.id, session, items });
    let attempt = additionAttempts.get(key);
    try { attempt ||= JSON.parse(sessionStorage.getItem(key) || 'null'); } catch { /* Use the in-memory attempt. */ }
    if (!attempt || attempt.fingerprint !== fingerprint) attempt = { fingerprint, requestId: generateClientOrderNo() };
    additionAttempts.set(key, attempt);
    try { sessionStorage.setItem(key, JSON.stringify(attempt)); } catch { /* Keep retry protection in memory. */ }
    const result = await addOrderItems(order.id, { requestId: attempt.requestId, tableSessionCode: session, items });
    if (result.error || !result.data) { message.warning('加菜尚未确认，所选菜品已保留；重试不会重复加入同一批菜'); return; }
    additionAttempts.delete(key);
    try { sessionStorage.removeItem(key); } catch { /* Nothing else to clear. */ }
    message.success(`${table.code} 桌加菜成功，已合入原账单`);
    clearCart(); showCartModal.value = false; showSubmitConfirm.value = false; preOrderMode.value = false;
    await refreshOrderingMenu();
  } finally { submitting.value = false; }
}

async function submitOrder() {
  if (submitting.value) return;
  if (!selectedTableId.value) {
    message.warning('请先选择桌台');
    return;
  }
  if (!preOrderMode.value && selectedTableIsPaid.value) {
    message.warning(`桌台 ${selectedTable.value?.code || ''} 已结账，请先完成清洁流转后再开台点单`);
    return;
  }
  if (!preOrderMode.value && selectedTableIsToClean.value) {
    message.warning(`桌台 ${selectedTable.value?.code || ''} 待清洁，请先完成清洁后再开台点单`);
    return;
  }
  if (cart.value.length === 0) {
    message.warning('请先添加菜品');
    return;
  }
  if (selectedTableHasActiveOrder.value) { await submitAddition(); return; }
  if (!Number.isInteger(guestCount.value) || !guestCount.value || guestCount.value < 1 || guestCount.value > 99) { message.warning('请确认本桌用餐人数'); return; }
  submitting.value = true;
  try {
    const table = selectedTable.value;
    const clientOrderNo = generateClientOrderNo();
    const payload: Api.Business.AdminOrderCreate = {
      tableId: selectedTableId.value,
      guestCount: guestCount.value!,
      tableCode: table?.code,
      clientOrderNo,
      items: cart.value.map(c => ({ dishId: c.dishId, quantity: c.quantity, remark: c.remark || undefined })),
      paymentMode: 1, // 餐后付
      orderType: 0,
      preOrder: preOrderMode.value
    };

    if (isOffline.value) {
      await saveOrderToOfflineQueue(payload, clientOrderNo);
      message.warning('当前离线，订单已缓存，联网后自动补传');
      clearCart();
      showCartModal.value = false;
      showSubmitConfirm.value = false;
      preOrderMode.value = false;
      return;
    }

    const { error } = await createAdminOrder(payload);
    if (!error) {
      message.success(preOrderMode.value ? '预订单已保存' : '下单成功');
      await refreshOrderingMenu();
      clearCart();
      showCartModal.value = false;
      showSubmitConfirm.value = false;
      preOrderMode.value = false;
      await refreshPendingOfflineCount();
      return;
    }

    if (isNetworkLikeError(error)) {
      await saveOrderToOfflineQueue(payload, clientOrderNo);
      message.warning('网络异常，订单已转入离线队列，恢复后自动补传');
      clearCart();
      showCartModal.value = false;
      showSubmitConfirm.value = false;
      preOrderMode.value = false;
    }
  } finally { submitting.value = false; }
}

let stopMenuSubscription: (() => void) | null = null;
async function refreshOrderingMenu() {
  if (isOffline.value) return;
  const [{ data, error }, tableResult] = await Promise.all([fetchDishCategoryList(), fetchTableList()]);
  if (!tableResult.error && tableResult.data) tables.value = tableResult.data;
  if (!error && data) {
    categories.value = data.filter(c => c.status === 1);
    if (activeCategoryId.value !== null && !categories.value.some(c => String(c.id) === String(activeCategoryId.value))) activeCategoryId.value = categories.value[0]?.id || null;
  }
  await loadAllDishes(true);
}
onActivated(() => { void nextTick(updatePageHeight); if (pageInitialized.value) void refreshOrderingMenu(); });

onMounted(async () => {
  connectWebSocket();
  stopMenuSubscription = subscribe('/topic/sold-out', () => { void refreshOrderingMenu(); });
  window.addEventListener('online', handleOnline);
  window.addEventListener('offline', handleOffline);
  window.addEventListener('resize', updatePageHeight);
  window.visualViewport?.addEventListener('resize', updatePageHeight);
  await nextTick(); updatePageHeight();
  loading.value = true;
  try {
    const [tableRes, catRes] = await Promise.all([fetchTableList(), fetchDishCategoryList()]);
    if (!tableRes.error && tableRes.data) tables.value = tableRes.data;
    if (!catRes.error && catRes.data) { categories.value = catRes.data.filter(c => c.status === 1); activeCategoryId.value = categories.value[0]?.id || null; }
    initSelectedTableFromRoute();
    await loadAllDishes();
    if (!isOffline.value) {
      void syncOfflineOrders();
    }
    void refreshPendingOfflineCount();
    pageInitialized.value = true;
  } finally { loading.value = false; await nextTick(); updatePageHeight(); }
});

onUnmounted(() => {
  stopMenuSubscription?.();
  window.removeEventListener('online', handleOnline);
  window.removeEventListener('offline', handleOffline);
  window.removeEventListener('resize', updatePageHeight);
  window.visualViewport?.removeEventListener('resize', updatePageHeight);
});
useMerchantOverlays(showCartModal, showTablePicker, showTableSwitch, showSubmitConfirm);
</script>

<template>
  <div ref="pageRoot" class="service-place-order-page" :class="{'waiter-compact':compactOrdering}" :style="{ height: pageHeight + 'px' }">
    <div class="waiter-workspace-shell">
      <!-- 顶部：桌台选择 + 搜索 -->
      <NCard :bordered="false" class="order-toolbar">
        <div class="place-order-tools">
          <NTag v-if="!compactOrdering || isOffline" :type="isOffline ? 'error' : 'success'">
            {{ isOffline ? '离线模式' : '在线模式' }}
          </NTag>
          <NTag v-if="pendingOfflineCount > 0" type="warning">
            待补传 {{ pendingOfflineCount }} 单
          </NTag>
          <NButton
            v-if="!isOffline && pendingOfflineCount > 0"
            size="small"
            :loading="syncingOfflineOrders"
            @click="syncOfflineOrders(true)"
          >
            立即补传
          </NButton>
          <NSelect
            :value="selectedTableId"
            :options="tableOptions"
            placeholder="选择桌台（含区域）"
            filterable
            :disabled="loading || submitting"
            :style="{ width: compactOrdering ? '100%' : '280px' }"
            @update:value="value => chooseTable(Number(value))"
          />
          <strong v-if="selectedTable && !compactOrdering" class="selected-table-reminder">正在为 {{ selectedTable.code }} 桌{{ selectedTableHasActiveOrder ? '加菜' : '点餐' }}</strong>
          <NButton v-else-if="!selectedTable && !compactOrdering" class="select-table-prompt" @click="tableSearch = ''; showTablePicker = true">先选桌台</NButton>
          <div class="waiter-search-controls">
          <NInput
            :value="searchKeyword"
            placeholder="搜索菜品..."
            clearable
            :disabled="loading || submitting"
            class="waiter-search-input"
            @update:value="handleSearch"
          />
          <NButton :loading="dishLoading" @click="refreshOrderingMenu">{{ compactOrdering ? '刷新' : '刷新菜单' }}</NButton>
          </div>

        </div>
      </NCard>

      <div class="waiter-workspace">
        <nav class="waiter-category-panel" aria-label="菜品分类">
          <h3>菜品分类</h3>
          <button type="button" :class="{active: activeCategoryId === null}" @click="selectCategory(null)">全部 <small>{{ allDishes.length }}</small></button>
          <button v-for="cat in categories" :key="cat.id" type="button" :class="{active: String(activeCategoryId) === String(cat.id)}" @click="selectCategory(cat.id)">{{ cat.name }} <small>{{ categoryDishCountMap.get(String(cat.id)) || 0 }}</small></button>
        </nav>
        <section class="waiter-menu-panel" aria-label="可点菜品">
          <div class="waiter-menu-heading"><h3>{{ searchKeyword ? '搜索结果' : categories.find(c => String(c.id) === String(activeCategoryId))?.name || '全部菜品' }}</h3><span>{{ dishList.length }} 道菜</span></div>
          <div class="waiter-menu-scroll">
            <NSpin :show="dishLoading || !pageInitialized">
              <div class="waiter-dish-grid">
                <article v-for="dish in dishList" :key="dish.id" class="dish-card" :class="{'dish-card--active':cartDishCountMap.has(String(dish.id))}">
                  <div class="dish-card__body">
                    <div class="dish-card__content"><div class="dish-card__name">{{ dish.name }}</div><div class="dish-card__tags"><span class="dish-card__price">¥{{ dish.price.toFixed(2) }}</span><NTag v-if="dish.soldOut === 1" type="error" size="small">卖完了</NTag><span v-else-if="dish.spiceLevel > 0" class="dish-spice">{{ ['','微辣','中辣','重辣'][dish.spiceLevel] || '辣味' }}</span></div></div>
                    <NImage v-if="dish.image" class="dish-card__image" :src="dish.image" object-fit="cover" preview-disabled />
                  </div>
                  <div class="dish-card__quantity">
                    <button v-if="cartDishCountMap.has(String(dish.id))" type="button" class="dish-qty-button" :disabled="submitting" :aria-label="'减少'+dish.name+'一份'" @click="updateQuantity(dish.id,(cartDishCountMap.get(String(dish.id)) || 0)-1)">−</button>
                    <span v-if="cartDishCountMap.has(String(dish.id))" class="dish-qty-count">{{ cartDishCountMap.get(String(dish.id)) }}</span>
                    <button type="button" class="dish-qty-button dish-qty-button--plus" :disabled="submitting || dish.soldOut === 1" :aria-label="'增加'+dish.name+'一份'" @click="addToCart(dish)">＋</button>
                  </div>
                </article>
              </div>
              <NEmpty v-if="pageInitialized && !dishLoading && !dishList.length" description="没有找到菜品，试试其他分类或菜名" />
            </NSpin>
          </div>
        </section>
        <aside class="waiter-order-panel" aria-label="本次待提交清单">
          <header><div><h3>{{ selectedTable ? selectedTable.code + ' 桌' : '待选桌台' }}</h3><span>{{ selectedTableHasActiveOrder ? '本次加菜 · 合入原账单' : '本次选菜 · 尚未提交' }}</span></div><NButton quaternary :disabled="submitting || !cart.length" @click="clearCart">清空</NButton></header>
          <div class="waiter-order-scroll">
            <div v-if="!cart.length" class="waiter-order-empty">先在左侧选几道菜<br><small>已选菜品和备注会显示在这里</small></div>
            <article v-for="item in cart" :key="item.dishId" class="waiter-order-item">
              <div class="waiter-order-item__head"><strong>{{ item.dishName }}</strong><span>¥{{ (item.price * item.quantity).toFixed(2) }}</span></div>
              <div class="waiter-order-item__controls"><small>¥{{ item.price.toFixed(2) }} / 份</small><div class="dish-card__quantity"><button class="dish-qty-button" :disabled="submitting" :aria-label="'减少'+item.dishName+'一份'" @click="updateQuantity(item.dishId,item.quantity-1)">−</button><span class="dish-qty-count">{{ item.quantity }}</span><button class="dish-qty-button dish-qty-button--plus" :disabled="submitting" :aria-label="'增加'+item.dishName+'一份'" @click="updateQuantity(item.dishId,item.quantity+1)">＋</button></div></div>
              <NInput v-model:value="item.remark" :disabled="submitting" placeholder="口味备注（选填）" maxlength="150" :aria-label="item.dishName+'口味备注'" />
            </article>
          </div>
          <footer class="waiter-order-footer"><div><span>共 {{ cartCount }} 份</span><strong>¥{{ cartTotalText }}</strong></div><NCheckbox v-if="!selectedTableHasActiveOrder" v-model:checked="preOrderMode" :disabled="submitting">预订单（暂不通知后厨）</NCheckbox><NButton type="primary" block :loading="submitting" :disabled="!cart.length" @click="requestSubmit">{{ selectedTableHasActiveOrder ? '确认加菜' : preOrderMode ? '保存预订单' : '提交订单' }}</NButton></footer>
        </aside>
      </div>
    </div>

    <!-- 购物车弹窗 -->
    <NModal :trap-focus="!appStore.isMobile" v-model:show="showCartModal" preset="card" :mask-closable="!submitting" :close-on-esc="!submitting" :closable="!submitting" title="购物车" style="width: 560px;">
      <template v-if="cart.length > 0">
        <p v-if="selectedTableHasActiveOrder" class="waiter-addition-hint">{{ selectedTable?.code }} 桌已有账单，本次选菜会作为加菜合入原账单。</p>
        <p v-else-if="selectedTable" class="waiter-addition-hint">本次选菜将提交到 {{ selectedTable.code }} 桌。</p>
        <NList bordered>
          <NListItem v-for="item in cart" :key="item.dishId">
            <NThing :title="item.dishName" :description="`¥${item.price.toFixed(2)}`">
              <template #header-extra>
                <NSpace align="center" :size="8">
                  <NButton class="waiter-cart-qty-button" :disabled="submitting" :aria-label="'减少'+item.dishName+'一份'" @click="updateQuantity(item.dishId, item.quantity - 1)">−</NButton>
                  <span style="min-width: 20px; text-align: center;">{{ item.quantity }}</span>
                  <NButton class="waiter-cart-qty-button" :disabled="submitting" :aria-label="'增加'+item.dishName+'一份'" @click="updateQuantity(item.dishId, item.quantity + 1)">＋</NButton>
                  <span style="font-weight: 600; min-width: 60px; text-align: right;">¥{{ (item.price * item.quantity).toFixed(2) }}</span>
                </NSpace>
              </template>
            </NThing>
            <NInput :disabled="submitting" v-model:value="item.remark" placeholder="备注（可选）" size="small" style="margin-top: 4px;" />
          </NListItem>
        </NList>
        <NDivider />
        <NSpace justify="space-between" align="center">
          <NButton @click="clearCart" :disabled="submitting">清空</NButton>
          <NSpace align="center" :size="16">
            <NCheckbox v-if="!selectedTableHasActiveOrder" v-model:checked="preOrderMode">预订单（不立即下发后厨）</NCheckbox>
            <span style="font-size: 16px; font-weight: 600;">合计：¥{{ cartTotalText }}</span>
            <NButton type="primary" :loading="submitting" @click="requestSubmit">
              {{ selectedTableHasActiveOrder ? '确认加菜' : preOrderMode ? '保存预订单' : '提交订单' }}
            </NButton>
          </NSpace>
        </NSpace>
      </template>
      <NEmpty v-else description="购物车为空" />
    </NModal>
    <NModal v-model:show="showTablePicker" preset="card" title="先选桌台，再点菜" :trap-focus="!appStore.isMobile" style="width:440px" @update:show="visible => { if (!visible) pendingDish = null }">
      <p class="table-picker-help">{{ pendingDish ? `选好桌台后，自动把「${pendingDish.name}」加入清单。` : '选择这次点餐的桌台。' }}</p>
      <NInput v-model:value="tableSearch" placeholder="搜索桌号或区域" clearable aria-label="搜索桌台" />
      <div class="table-picker-list">
        <NButton v-for="table in availableTables" :key="table.id" block class="table-picker-item" @click="chooseTable(table.id)">
          <strong>{{ table.code }} 桌 · {{ table.areaName || '未分区' }}</strong>
          <small>{{ table.status === 1 ? '已有账单 · 加菜' : '还没下单 · 新开单' }}</small>
        </NButton>
        <NEmpty v-if="!availableTables.length" description="没有可用桌台，请刷新桌台状态" />
      </div>
    </NModal>
    <NModal v-model:show="showTableSwitch" preset="card" title="确认切换桌台" :trap-focus="!appStore.isMobile" style="width:420px">
      <p class="table-picker-help">{{ selectedTable?.code }} 桌的清单已有 {{ cartCount }} 份菜。切到 {{ tables.find(table => table.id === pendingTableId)?.code }} 桌会清空这份待提交清单。</p>
      <NSpace justify="end">
        <NButton @click="showTableSwitch = false; pendingTableId = null">继续点当前桌</NButton>
        <NButton type="warning" @click="confirmTableSwitch">清空并切换</NButton>
      </NSpace>
    </NModal>
    <NModal v-model:show="showSubmitConfirm" preset="card" title="最后核对桌台" :trap-focus="!appStore.isMobile" :mask-closable="!submitting" :close-on-esc="!submitting" :closable="!submitting" style="width:440px">
      <div class="table-submit-summary"><strong>{{ selectedTable?.code }} 桌</strong><span>{{ selectedTableHasActiveOrder ? '给原账单加菜' : preOrderMode ? '保存预订单' : '首次下单' }}</span><p>本次 {{ cartCount }} 份菜 · 合计 ¥{{ cartTotalText }}</p></div>
      <div v-if="!selectedTableHasActiveOrder" class="tableware-confirm"><label>本桌用餐人数<NInputNumber v-model:value="guestCount" :min="1" :max="99" :precision="0" placeholder="请填写人数" /></label><p>每人一套餐具，1元/套 · 餐具费 ¥{{ (guestCount || 0).toFixed(2) }}</p><strong>本次含餐具合计 ¥{{ (Number(cartTotalText) + (guestCount || 0)).toFixed(2) }}</strong></div>
      <p v-else>给原账单加菜，餐具费不重复收取。</p>
      <NSpace justify="end">
        <NButton :disabled="submitting" @click="showSubmitConfirm = false">返回核对</NButton>
        <NButton type="primary" :loading="submitting" @click="submitOrder">确认提交到 {{ selectedTable?.code }} 桌</NButton>
      </NSpace>
    </NModal>
    <div v-if="compactOrdering" class="waiter-cart-bar" aria-label="待提交清单">
      <div><strong>{{ selectedTable ? `${selectedTable.code} 桌 · 已选 ${cartCount} 份` : '还没选桌台' }}</strong><span>合计 ¥{{ cartTotalText }}</span></div>
      <div class="waiter-mobile-actions"><NButton :disabled="submitting" @click="showCartModal = true">清单</NButton><NButton type="primary" :loading="submitting" :disabled="!cart.length" @click="requestSubmit">{{ selectedTableHasActiveOrder ? '确认加菜' : '提交订单' }}</NButton></div>
    </div>
  </div>
</template>

<style scoped src="./workspace.css"></style>
