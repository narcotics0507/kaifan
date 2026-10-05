<script setup lang="ts">
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import { useAppStore } from '@/store/modules/app';
import { computed, nextTick, onActivated, onMounted, onUnmounted, ref } from 'vue';
import {
  NCard, NSpace, NGrid, NGi, NButton, NInput, NSelect,
  NTag, NSpin, NEmpty, NBadge, NModal, NList, NListItem, NThing, NImage,
  NCheckbox,
  NScrollbar, NDivider, NSkeleton, useMessage
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

// ==================== 桌台选择 ====================
const tables = ref<Api.Business.DiningTable[]>([]);
const selectedTableId = ref<number | null>(null);

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
    const matchCategory = !activeCategoryId.value || String(dish.categoryId) === String(activeCategoryId.value);
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
const cartButtonRef = ref<HTMLElement | null>(null);
const flyToCartToken = ref({
  visible: false,
  active: false,
  label: '',
  x: 0,
  y: 0,
  targetX: 0,
  targetY: 0
});
let flyTokenTimer: number | null = null;

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

async function playFlyToCartEffect(dish: Api.Business.Dish, event?: MouseEvent) {
  const startElement = event?.currentTarget as HTMLElement | null;
  const cartElement = cartButtonRef.value;
  if (!startElement || !cartElement) return;

  const startRect = startElement.getBoundingClientRect();
  const cartRect = cartElement.getBoundingClientRect();

  if (flyTokenTimer) {
    window.clearTimeout(flyTokenTimer);
    flyTokenTimer = null;
  }

  flyToCartToken.value = {
    visible: true,
    active: false,
    label: `+1 ${dish.name}`,
    x: startRect.left + startRect.width / 2 - 42,
    y: startRect.top + 12,
    targetX: cartRect.left + cartRect.width / 2 - 42,
    targetY: cartRect.top + 6
  };

  await nextTick();
  requestAnimationFrame(() => {
    flyToCartToken.value.active = true;
  });

  flyTokenTimer = window.setTimeout(() => {
    flyToCartToken.value.visible = false;
    flyToCartToken.value.active = false;
    flyTokenTimer = null;
  }, 720);
}

/** 添加菜品到购物车 */
function addToCart(dish: Api.Business.Dish, event?: MouseEvent) {
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
  void playFlyToCartEffect(dish, event);
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
  submitting.value = true;
  try {
    const table = selectedTable.value;
    const clientOrderNo = generateClientOrderNo();
    const payload: Api.Business.AdminOrderCreate = {
      tableId: selectedTableId.value,
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
    if (!categories.value.some(c => String(c.id) === String(activeCategoryId.value))) activeCategoryId.value = null;
  }
  await loadAllDishes(true);
}
onActivated(() => { if (pageInitialized.value) void refreshOrderingMenu(); });

onMounted(async () => {
  connectWebSocket();
  stopMenuSubscription = subscribe('/topic/sold-out', () => { void refreshOrderingMenu(); });
  window.addEventListener('online', handleOnline);
  window.addEventListener('offline', handleOffline);
  loading.value = true;
  try {
    const [tableRes, catRes] = await Promise.all([fetchTableList(), fetchDishCategoryList()]);
    if (!tableRes.error && tableRes.data) tables.value = tableRes.data;
    if (!catRes.error && catRes.data) categories.value = catRes.data.filter(c => c.status === 1);
    initSelectedTableFromRoute();
    await loadAllDishes();
    if (!isOffline.value) {
      void syncOfflineOrders();
    }
    void refreshPendingOfflineCount();
    pageInitialized.value = true;
  } finally { loading.value = false; }
});

onUnmounted(() => {
  stopMenuSubscription?.();
  window.removeEventListener('online', handleOnline);
  window.removeEventListener('offline', handleOffline);
});
useMerchantOverlays(showCartModal, showTablePicker, showTableSwitch, showSubmitConfirm);
</script>

<template>
  <div class="service-place-order-page">
    <NSpace vertical :size="12">
      <!-- 顶部：桌台选择 + 搜索 -->
      <NCard :bordered="false" class="order-toolbar">
        <div class="place-order-tools">
          <NTag :type="isOffline ? 'error' : 'success'">
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
            :style="{ width: appStore.isMobile ? '100%' : '280px' }"
            @update:value="value => chooseTable(Number(value))"
          />
          <strong v-if="selectedTable" class="selected-table-reminder">正在为 {{ selectedTable.code }} 桌{{ selectedTableHasActiveOrder ? '加菜' : '点餐' }}</strong>
          <NButton v-else class="select-table-prompt" @click="tableSearch = ''; showTablePicker = true">先选桌台</NButton>
          <NInput
            :value="searchKeyword"
            placeholder="搜索菜品..."
            clearable
            :disabled="loading || submitting"
            :style="{ width: appStore.isMobile ? '100%' : '240px' }"
            @update:value="handleSearch"
          />
          <NButton :loading="dishLoading" @click="refreshOrderingMenu">刷新菜单</NButton>
          <NBadge :value="cartCount" :max="99">
            <NButton ref="cartButtonRef" type="primary" @click="showCartModal = true">
              购物车 ¥{{ cartTotal.toFixed(2) }}
            </NButton>
          </NBadge>
        </div>
      </NCard>

      <NGrid :cols="24" :x-gap="appStore.isMobile ? 0 : 16">
        <!-- 左侧：分类导航 -->
        <NGi :span="appStore.isMobile ? 24 : 4">
          <NCard :bordered="false" title="菜品分类" size="small" class="ordering-categories">
            <NScrollbar :style="{ maxHeight: appStore.isMobile ? 'none' : 'calc(100vh - 240px)' }">
              <NSpace v-if="pageInitialized" :vertical="!appStore.isMobile" :size="6" class="ordering-category-buttons">
                <NButton
                  :type="activeCategoryId === null ? 'primary' : 'default'"
                  block
                  size="small"
                  @click="selectCategory(null)"
                >
                  全部（{{ allDishes.length }}）
                </NButton>
                <NButton
                  v-for="cat in categories"
                  :key="cat.id"
                  :type="activeCategoryId === cat.id ? 'primary' : 'default'"
                  block
                  size="small"
                  @click="selectCategory(cat.id)"
                >
                  {{ cat.name }}（{{ categoryDishCountMap.get(String(cat.id)) || 0 }}）
                </NButton>
              </NSpace>
              <NSpace v-else vertical :size="8">
                <NSkeleton v-for="idx in 6" :key="idx" height="34px" :sharp="false" style="border-radius: 12px;" />
              </NSpace>
            </NScrollbar>
          </NCard>
        </NGi>

        <!-- 右侧：菜品列表 -->
        <NGi :span="appStore.isMobile ? 24 : 20">
          <NSpin :show="pageInitialized && dishLoading">
            <div v-if="!pageInitialized" class="dish-skeleton-grid">
              <div v-for="idx in 8" :key="idx" class="dish-skeleton-card">
                <NSkeleton height="20px" width="42%" :sharp="false" />
                <NSkeleton text :repeat="2" :sharp="false" style="margin-top: 10px;" />
                <div class="dish-skeleton-card__foot">
                  <NSkeleton height="18px" width="72px" :sharp="false" />
                  <NSkeleton circle height="58px" width="58px" />
                </div>
              </div>
            </div>
            <NGrid :cols="4" :x-gap="12" :y-gap="12" responsive="screen" :item-responsive="true">
              <NGi v-for="dish in dishList" :key="dish.id" span="4 m:2 l:1">
                <NCard
                  class="dish-card"
                  :class="{ 'dish-card--active': cartDishCountMap.has(String(dish.id)) }"
                  size="small"
                  hoverable
                  :style="{ opacity: dish.soldOut === 1 ? 0.5 : 1, cursor: dish.soldOut === 1 ? 'not-allowed' : 'pointer' }"
                  @click="addToCart(dish, $event)"
                >
                  <div class="dish-card__body">
                    <div class="dish-card__content">
                      <div class="dish-card__name">{{ dish.name }}</div>
                      <NSpace :size="8" align="center" class="dish-card__tags">
                        <span class="dish-card__price">¥{{ dish.price.toFixed(2) }}</span>
                        <NTag v-if="dish.soldOut === 1" type="error" size="small">卖完了</NTag>
                        <NTag v-if="dish.spiceLevel > 0" type="warning" size="small">
                          辣度 {{ dish.spiceLevel }}
                        </NTag>
                      </NSpace>
                      <div v-if="dish.categoryName" class="dish-card__meta">{{ dish.categoryName }}</div>
                      <div v-if="cartDishCountMap.has(String(dish.id))" class="dish-card__feedback">
                        已选 {{ cartDishCountMap.get(String(dish.id)) }} 份
                      </div>
                    </div>
                    <div class="dish-card__media">
                      <NImage
                        v-if="dish.image"
                        class="dish-card__image"
                        :src="dish.image"
                        object-fit="cover"
                        preview-disabled
                      />
                      <div v-else class="dish-card__placeholder">
                        {{ dish.name.slice(0, 2) }}
                      </div>
                    </div>
                  </div>
                  <div class="dish-card__quantity" @click.stop>
                    <button
                      v-if="cartDishCountMap.has(String(dish.id))"
                      type="button"
                      class="dish-qty-button"
                      :disabled="submitting"
                      :aria-label="'减少'+dish.name+'一份'"
                      @click.stop="updateQuantity(dish.id, (cartDishCountMap.get(String(dish.id)) || 0) - 1)"
                    >−</button>
                    <span v-if="cartDishCountMap.has(String(dish.id))" class="dish-qty-count" aria-live="polite">{{ cartDishCountMap.get(String(dish.id)) }}</span>
                    <button
                      type="button"
                      class="dish-qty-button dish-qty-button--plus"
                      :disabled="submitting || dish.soldOut === 1"
                      :aria-label="'增加'+dish.name+'一份'"
                      @click.stop="addToCart(dish, $event)"
                    >＋</button>
                  </div>
                </NCard>
              </NGi>
            </NGrid>
            <NEmpty v-if="!dishLoading && dishList.length === 0" description="暂无菜品" style="padding: 40px;" />
          </NSpin>
        </NGi>
      </NGrid>
    </NSpace>

    <div
      v-if="flyToCartToken.visible"
      class="cart-fly-token"
      :class="{ 'cart-fly-token--active': flyToCartToken.active }"
      :style="{
        left: `${flyToCartToken.active ? flyToCartToken.targetX : flyToCartToken.x}px`,
        top: `${flyToCartToken.active ? flyToCartToken.targetY : flyToCartToken.y}px`
      }"
    >
      {{ flyToCartToken.label }}
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
      <NSpace justify="end">
        <NButton :disabled="submitting" @click="showSubmitConfirm = false">返回核对</NButton>
        <NButton type="primary" :loading="submitting" @click="submitOrder">确认提交到 {{ selectedTable?.code }} 桌</NButton>
      </NSpace>
    </NModal>
    <div v-if="appStore.isMobile" class="waiter-cart-bar" aria-label="待提交清单">
      <div><strong>{{ selectedTable ? `${selectedTable.code} 桌 · 已选 ${cartCount} 份` : '还没选桌台' }}</strong><span>合计 ¥{{ cartTotalText }}</span></div>
      <NButton type="primary" :disabled="submitting" @click="showCartModal = true">查看清单{{ cartCount ? `（${cartCount}）` : '' }}</NButton>
    </div>
  </div>
</template>

<style scoped>
.waiter-addition-hint{margin:0 0 16px;color:var(--restaurant-muted,#796252);font-size:.875rem;line-height:1.6}
.selected-table-reminder{display:inline-flex;align-items:center;min-height:40px;padding:6px 10px;border-radius:10px;background:#f3e4d5;color:#93401c;font-size:.875rem;white-space:nowrap}
.select-table-prompt{min-height:44px}
.table-picker-help{color:var(--restaurant-muted,#796252);font-size:.875rem;line-height:1.6}
.table-picker-list{display:grid;gap:8px;max-height:min(50vh,400px);overflow:auto;margin-top:14px}
.table-picker-item{min-height:54px;height:auto!important;padding:8px 12px!important}
.table-picker-item :deep(.n-button__content){display:flex;justify-content:space-between;align-items:center;gap:12px;width:100%;white-space:normal;text-align:left}
.table-picker-item small{font-size:.75rem;color:var(--restaurant-muted,#796252)}
.table-submit-summary{margin:6px 0 18px;padding:16px;border-radius:12px;background:#f5eee5}
.table-submit-summary strong{font-size:1.375rem;color:#93401c}.table-submit-summary span{margin-left:12px;font-size:.875rem}.table-submit-summary p{font-size:1rem;font-weight:700;margin:12px 0 0}
.dish-card__quantity{display:flex;align-items:center;justify-content:flex-end;gap:6px;margin-top:10px;min-height:44px}
.dish-qty-button{display:grid;place-items:center;width:44px;height:44px;flex:0 0 44px;border:1px solid #d9b79e;border-radius:12px;background:#fffaf3;color:#95451f;font-family:inherit;font-size:1.375rem;font-weight:700;line-height:1;cursor:pointer;touch-action:manipulation}
.dish-qty-button--plus{background:#a94d24;color:#fff;border-color:#a94d24}
.dish-qty-button:disabled{opacity:.5;cursor:default}
.dish-qty-button:focus-visible{outline:3px solid #a94d24;outline-offset:2px}
.dish-qty-count{min-width:1.5em;text-align:center;font-size:1rem;font-weight:700;font-variant-numeric:tabular-nums}
.waiter-cart-qty-button{min-width:44px!important;min-height:44px!important;padding:0!important}
.waiter-cart-bar{display:none}
.place-order-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.order-toolbar {
  position:sticky;top:0;z-index:12;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(239, 247, 255, 0.96)) !important;
}

.dish-skeleton-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.dish-skeleton-card {
  padding: 14px;
  border-radius: 20px;
  background:
    radial-gradient(circle at top right, rgba(15, 111, 255, 0.05), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(243, 249, 255, 0.82));
  border: 1px solid rgba(15, 111, 255, 0.08);
}

.dish-skeleton-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
}

.dish-card {
  overflow: hidden;
  border-radius: 20px;
  background:
    radial-gradient(circle at top right, rgba(15, 111, 255, 0.08), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(243, 249, 255, 0.82)) !important;
  border: 1px solid rgba(15, 111, 255, 0.1);
  box-shadow:
    0 18px 34px rgba(15, 57, 119, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    border-color 0.22s ease;
}

.dish-card:hover {
  transform: translateY(-5px);
  box-shadow:
    0 24px 44px rgba(15, 57, 119, 0.14),
    0 10px 24px rgba(8, 27, 58, 0.06);
}

.dish-card--active {
  border-color: rgba(var(--admin-accent-rgb), 0.2);
  box-shadow:
    0 24px 42px rgba(var(--admin-accent-rgb), 0.12),
    inset 0 0 0 1px rgba(var(--admin-accent-rgb), 0.08);
}

.dish-card__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dish-card__content {
  min-width: 0;
  flex: 1;
}

.dish-card__name {
  font-size: 14px;
  font-weight: 700;
  color: #123055;
}

.dish-card__tags {
  margin-top: 8px;
  flex-wrap: wrap;
}

.dish-card__price {
  color: #d03050;
  font-weight: 700;
}

.dish-card__meta {
  margin-top: 8px;
  font-size: 11px;
  color: #7a8ca8;
}

.dish-card__feedback {
  margin-top: 8px;
  font-size: 11px;
  font-weight: 700;
  color: var(--admin-accent-strong);
}

.dish-card__media {
  flex-shrink: 0;
}

.dish-card__image,
.dish-card__placeholder {
  width: 58px;
  height: 58px;
  border-radius: 16px;
}

.dish-card__placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, rgba(15, 111, 255, 0.14), rgba(20, 163, 255, 0.08));
  border: 1px solid rgba(15, 111, 255, 0.12);
  font-size: 14px;
  font-weight: 700;
  color: #0f6fff;
}

.cart-fly-token {
  position: fixed;
  z-index: 1200;
  padding: 8px 12px;
  border-radius: 999px;
  background: linear-gradient(135deg, var(--admin-accent-gradient-start), var(--admin-accent-gradient-end));
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  pointer-events: none;
  box-shadow: 0 16px 28px rgba(var(--admin-accent-rgb), 0.24);
  opacity: 0.92;
  transform: scale(0.96);
  transition:
    left 0.68s cubic-bezier(0.2, 0.8, 0.2, 1),
    top 0.68s cubic-bezier(0.2, 0.8, 0.2, 1),
    transform 0.68s cubic-bezier(0.2, 0.8, 0.2, 1),
    opacity 0.68s ease;
}

.cart-fly-token--active {
  opacity: 0.2;
  transform: scale(0.72);
}

@media (max-width: 960px) {
  .dish-skeleton-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

html.dark .order-toolbar {
  background: linear-gradient(180deg, rgba(9, 13, 21, 0.96), rgba(14, 19, 30, 0.98)) !important;
}

html.dark .dish-card {
  background:
    radial-gradient(circle at top right, rgba(var(--admin-accent-rgb), 0.12), transparent 28%),
    linear-gradient(180deg, rgba(12, 17, 28, 0.96), rgba(8, 12, 20, 0.96)) !important;
  border-color: rgba(255, 255, 255, 0.06);
  box-shadow:
    0 18px 34px rgba(0, 0, 0, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

html.dark .dish-card:hover {
  box-shadow:
    0 24px 44px rgba(0, 0, 0, 0.34),
    0 10px 24px rgba(0, 0, 0, 0.18);
}

html.dark .dish-card--active {
  border-color: rgba(var(--admin-accent-rgb), 0.24);
  box-shadow:
    0 22px 40px rgba(0, 0, 0, 0.3),
    inset 0 0 0 1px rgba(var(--admin-accent-rgb), 0.14);
}

html.dark .dish-card__name {
  color: rgba(241, 246, 255, 0.96);
}

html.dark .dish-card__meta {
  color: rgba(170, 186, 216, 0.72);
}

html.dark .dish-card__feedback {
  color: #dbe5ff;
}

html.dark .dish-card__placeholder {
  background: linear-gradient(180deg, rgba(var(--admin-accent-rgb), 0.18), rgba(var(--admin-accent-rgb), 0.08));
  border-color: rgba(var(--admin-accent-rgb), 0.14);
  color: #dbe5ff;
}

html.dark .cart-fly-token {
  box-shadow: 0 16px 30px rgba(0, 0, 0, 0.3);
}


@media (max-width: 639px) {
  .service-place-order-page{padding-bottom:84px}
  .waiter-cart-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;position:fixed;bottom:var(--merchant-nav-height,72px);left:0;right:0;z-index:1200;padding:10px 14px;background:var(--restaurant-paper,#fffaf3);border-top:1px solid var(--restaurant-line,#e9dfd2)}
  .waiter-cart-bar>div{min-width:0;display:grid;line-height:1.35}
  .waiter-cart-bar strong{font-size:.875rem}
  .waiter-cart-bar span{font-size:1.125rem;font-weight:700;color:#a94d24;font-variant-numeric:tabular-nums}
  .waiter-cart-bar .n-button{min-height:44px;flex-shrink:0}
  .place-order-tools { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .place-order-tools > * { min-width: 0; }
  .place-order-tools > :deep(.n-select), .place-order-tools > :deep(.n-input), .place-order-tools > :deep(.n-tag) { grid-column: 1 / -1; }
  .ordering-category-buttons :deep(.n-button) { width: auto; }
  .ordering-categories { margin-bottom: 12px; }
  .ordering-categories :deep(.n-card-header) { padding-bottom: 8px; }
  .dish-card__name { font-size: 17px; }
  .dish-card__price { font-size: 18px; }
  .dish-skeleton-grid { grid-template-columns: 1fr; }
}
</style>
