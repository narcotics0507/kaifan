<script setup lang="ts">
import DishPhotoPicker from '@/components/business/dish-photo-picker.vue';
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import { useAppStore } from '@/store/modules/app';
import { restaurantFeatures } from '@/config/restaurant';
import { computed, h, onMounted, ref } from 'vue';
import {
  NButton, NCard, NEmpty, NPagination, NDataTable, NForm, NFormItem, NGrid, NGi, NImage, NInput, NInputNumber, NModal, NPopconfirm,
  NSelect, NSpace, NTag, useMessage
} from 'naive-ui';
import type { DataTableColumns, FormInst, SelectOption } from 'naive-ui';
import {
  createDish,
  fetchDishCategoryList,
  fetchDishList,
  fetchDishSpecGroupList,
  updateDish,
  updateDishSoldOut,
  updateDishStatus
} from '@/service/api';

defineOptions({ name: 'DishList' });

const appStore = useAppStore();
const message = useMessage();
function toIdKey(value: string | number | null | undefined) {
  if (value === null || value === undefined) return '';
  return String(value);
}

const loading = ref(false);
const viewMode = ref<'cards' | 'table'>('cards');
const data = ref<Api.Business.Dish[]>([]);
const total = ref(0);
const categoryOptions = ref<SelectOption[]>([]);
const categoryMap = ref<Record<string, Api.Business.DishCategory>>({});
const specGroupMap = ref<Record<string, Api.Business.DishSpecGroup>>({});

const searchForm = ref<Api.Business.DishQuery & { pageNum: number; pageSize: number }>({
  pageNum: 1,
  pageSize: 10,
  categoryId: undefined,
  name: '',
  status: undefined
});

const showModal = ref(false);
const isEdit = ref(false);
const formRef = ref<FormInst | null>(null);
const formModel = ref<Api.Business.DishCreate & { id?: Api.Business.IdType }>({
  categoryId: 0,
  name: '',
  price: 0,
  spiceLevel: 0,
  stock: -1,
  specItems: []
});
const uploadLoading = ref(false), saving = ref(false), photoSession = ref(0);
const previewImageUrl = ref('');
const extraSpecGroupId = ref<Api.Business.IdType | null>(null);

const rules = {
  name: { required: true, message: '请输入菜品名称', trigger: 'blur' },
  price: { required: true, type: 'number' as const, message: '请输入价格', trigger: 'blur' }
};

const statusOptions = [
  { label: '全部', value: undefined },
  { label: '上架', value: 1 },
  { label: '下架', value: 0 }
];

const availableExtraSpecGroupOptions = computed(() => {
  const selectedIds = new Set((formModel.value.specItems || []).map(item => toIdKey(item.specGroupId)));
  return Object.values(specGroupMap.value)
    .filter(item => item.status === 1 && !selectedIds.has(toIdKey(item.id)))
    .map(item => ({ label: item.name, value: toIdKey(item.id) }));
});

const columns: DataTableColumns<Api.Business.Dish> = [
  { title: '菜品名称', key: 'name', width: 150 },
  {
    title: '图片',
    key: 'image',
    width: 96,
    render(row) {
      if (!row.image) return '-';
      return h(NImage, {
        src: row.image,
        width: 44,
        height: 44,
        objectFit: 'cover',
        style: 'border-radius: 6px; border: 1px solid #e5e7eb;'
      });
    }
  },
  { title: '分类', key: 'categoryName', width: 100, render: row => row.categoryName || '未分类' },
  { title: '价格', key: 'price', width: 88, render: row => `¥${row.price}` },
  { title: '库存', key: 'stock', width: 80, render: row => row.stock === -1 ? '不限' : String(row.stock) },
  {
    title: '状态',
    key: 'status',
    width: 80,
    render: row => h(NTag, { type: row.status === 1 ? 'success' : 'warning' }, { default: () => row.status === 1 ? '上架' : '下架' })
  },
  {
    title: '是否卖完',
    key: 'soldOut',
    width: 80,
    render: row => row.soldOut === 1 ? h(NTag, { type: 'error' }, { default: () => '卖完了' }) : '-'
  },
  { title: '创建时间', key: 'createTime', width: 170 },
  {
    title: '操作',
    key: 'actions',
    width: 320,
    render: row =>
      h(NSpace, null, {
        default: () => [
          h(NButton, { size: 'small', type: 'primary', onClick: () => handleEdit(row) }, { default: () => '编辑' }),
          h(NPopconfirm, { onPositiveClick: () => handleToggleSoldOut(row) }, {
            trigger: () => h(
              NButton,
              { size: 'small', type: row.soldOut === 1 ? 'success' : 'error', secondary: true },
              { default: () => row.soldOut === 1 ? '取消卖完标记' : '卖完了' }
            ),
            default: () => row.soldOut === 1 ? '确定取消卖完标记吗？' : '确定标记为卖完了？之后不能新点这道菜，已下单的菜保留。'
          }),
          h(NPopconfirm, { onPositiveClick: () => handleToggleStatus(row) }, {
            trigger: () => h(
              NButton,
              { size: 'small', type: row.status === 1 ? 'warning' : 'success' },
              { default: () => row.status === 1 ? '下架' : '上架' }
            ),
            default: () => `确定${row.status === 1 ? '下架' : '上架'}该菜品吗？`
          })
        ]
      })
  }
];

async function loadCategories() {
  const { data: result, error } = await fetchDishCategoryList();
  if (!error && result) {
    categoryMap.value = result.reduce<Record<string, Api.Business.DishCategory>>((acc, item) => {
      acc[toIdKey(item.id)] = item;
      return acc;
    }, {});
    categoryOptions.value = [
      { label: '未分类', value: 0 },
      ...result.map(item => ({ label: item.name, value: toIdKey(item.id) }))
    ];
  }
}

async function loadSpecGroups() {
  const { data: result, error } = await fetchDishSpecGroupList();
  if (!error && result) {
    specGroupMap.value = result.reduce<Record<string, Api.Business.DishSpecGroup>>((acc, item) => {
      acc[toIdKey(item.id)] = item;
      return acc;
    }, {});
  }
}

async function loadData() {
  loading.value = true;
  try {
    const { data: result, error } = await fetchDishList(searchForm.value);
    if (!error && result) {
      data.value = result.list;
      total.value = result.total;
    }
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  searchForm.value.pageNum = 1;
  loadData();
}

function handleReset() {
  searchForm.value = { pageNum: 1, pageSize: 10, categoryId: undefined, name: '', status: undefined };
  loadData();
}

function createSpecItemsFromCategory(categoryId: Api.Business.IdType) {
  const category = categoryMap.value[toIdKey(categoryId)];
  if (!category?.specGroupIds?.length) {
    return [];
  }
  return category.specGroupIds
    .map(specGroupId => buildDishSpecItem(specGroupId))
    .filter(Boolean) as Api.Business.DishSpecItem[];
}

function buildDishSpecItem(specGroupId: Api.Business.IdType) {
  const group = specGroupMap.value[toIdKey(specGroupId)];
  if (!group) {
    return null;
  }
  return {
    specGroupId: group.id,
    specGroupName: group.name,
    optionIds: group.options.map(item => toIdKey(item.id)).filter(Boolean),
    optionNames: group.options.map(item => item.name)
  };
}

function handleAdd() {
  photoSession.value += 1;
  uploadLoading.value = false;
  isEdit.value = false;
  formModel.value = { categoryId: 0, name: '', price: 0, spiceLevel: 0, stock: -1, specItems: [] };
  previewImageUrl.value = '';
  extraSpecGroupId.value = null;
  showModal.value = true;
}

function handleEdit(row: Api.Business.Dish) {
  photoSession.value += 1;
  uploadLoading.value = false;
  isEdit.value = true;
  formModel.value = {
    id: row.id,
    categoryId: row.categoryId ?? 0,
    name: row.name,
    price: row.price,
    image: row.image || undefined,
    thumbnail: row.thumbnail || undefined,
    spiceLevel: row.spiceLevel,
    ingredients: row.ingredients || undefined,
    description: row.description || undefined,
    stock: row.stock,
    preparationTime: row.preparationTime || undefined,
    specItems: (row.specItems || []).map(item => ({
      specGroupId: item.specGroupId,
      specGroupName: item.specGroupName,
      optionIds: [...item.optionIds],
      optionNames: [...item.optionNames]
    }))
  };
  previewImageUrl.value = row.image || '';
  extraSpecGroupId.value = null;
  showModal.value = true;
}

function handleCategoryChange(categoryId: Api.Business.IdType) {
  formModel.value.categoryId = categoryId || 0;
  if (restaurantFeatures.specs) formModel.value.specItems = createSpecItemsFromCategory(formModel.value.categoryId);
}

function addExtraSpecGroup() {
  if (!extraSpecGroupId.value) {
    return;
  }
  const nextItem = buildDishSpecItem(extraSpecGroupId.value);
  if (!nextItem) {
    return;
  }
  formModel.value.specItems = [...(formModel.value.specItems || []), nextItem];
  extraSpecGroupId.value = null;
}

function removeSpecItem(index: number) {
  formModel.value.specItems?.splice(index, 1);
}

function updateSpecItemOptions(index: number, optionIds: Api.Business.IdType[]) {
  const specItem = formModel.value.specItems?.[index];
  if (!specItem) {
    return;
  }
  specItem.optionIds = optionIds;
  specItem.optionNames = optionIds
    .map(optionId => specGroupMap.value[toIdKey(specItem.specGroupId)]?.options.find(item => toIdKey(item.id) === toIdKey(optionId))?.name || '')
    .filter(Boolean);
}

function getSpecOptionSelectOptions(specGroupId: Api.Business.IdType) {
  return (specGroupMap.value[toIdKey(specGroupId)]?.options || []).map(item => ({
    label: item.name,
    value: toIdKey(item.id)
  }));
}

async function handleSubmit() {
  if (uploadLoading.value || saving.value) return;
  await formRef.value?.validate();
  const payload: Api.Business.DishCreate & { id?: Api.Business.IdType } = {
    ...formModel.value,
    categoryId: formModel.value.categoryId || 0,
    spiceLevel: 0,
    specItems: (formModel.value.specItems || []).filter(item => item.optionIds?.length)
  };
  saving.value = true;
  try {
  if (isEdit.value && formModel.value.id) {
    const { error } = await updateDish(formModel.value.id, payload as Api.Business.DishUpdate);
    if (!error) {
      message.success('菜品已保存，顾客菜单将自动更新');
      showModal.value = false;
      loadData();
    }
    return;
  }

  const { error } = await createDish(payload);
  if (!error) {
    message.success('菜品已保存，顾客菜单将自动更新');
    showModal.value = false;
    loadData();
  }
  } finally { saving.value = false; }
}

async function handleToggleStatus(row: Api.Business.Dish) {
  const newStatus = row.status === 1 ? 0 : 1;
  const { error } = await updateDishStatus(row.id, newStatus);
  if (!error) {
    message.success('操作成功');
    loadData();
  }
}

async function handleToggleSoldOut(row: Api.Business.Dish) {
  const targetSoldOut = row.soldOut === 1 ? 0 : 1;
  const { error } = await updateDishSoldOut(row.id, targetSoldOut as 0 | 1);
  if (!error) {
    message.success(targetSoldOut === 1 ? '已标记卖完了' : '已取消卖完标记');
    loadData();
  }
}

function handlePageChange(page: number) {
  searchForm.value.pageNum = page;
  loadData();
}

function handlePageSizeChange(pageSize: number) {
  searchForm.value.pageSize = pageSize;
  searchForm.value.pageNum = 1;
  loadData();
}

function handleDishPhoto(result: Api.Business.FileUploadResult) {
  formModel.value.image = result.objectName;
  formModel.value.thumbnail = result.objectName;
  previewImageUrl.value = result.url;
}

function formatDishSpec(specItems?: Api.Business.DishSpecItem[]) {
  if (!specItems?.length) {
    return '-';
  }
  return specItems.map(item => `${item.specGroupName}：${item.optionNames.join('/')}`).join('；');
}

onMounted(async () => {
  await Promise.all([loadCategories(), ...(restaurantFeatures.specs ? [loadSpecGroups()] : [])]);
  loadData();
});
useMerchantOverlays(showModal);
</script>

<template>
  <NSpace vertical :size="16" class="dish-management">
    <NCard :bordered="false" class="dish-search-card">
      <NForm :model="searchForm" :label-placement="appStore.isMobile ? 'top' : 'left'" label-width="80">
        <NGrid :cols="24" :x-gap="appStore.isMobile ? 0 : 18">
          <NGi :span="appStore.isMobile ? 24 : 6">
            <NFormItem label="菜品名称">
              <NInput v-model:value="searchForm.name" placeholder="搜索菜名" clearable />
            </NFormItem>
          </NGi>
          <NGi :span="appStore.isMobile ? 24 : 5">
            <NFormItem label="分类">
              <NSelect v-model:value="searchForm.categoryId" :options="categoryOptions" placeholder="全部分类" clearable />
            </NFormItem>
          </NGi>
          <NGi :span="appStore.isMobile ? 24 : 5">
            <NFormItem label="状态">
              <NSelect v-model:value="searchForm.status" :options="statusOptions" placeholder="全部" clearable />
            </NFormItem>
          </NGi>
          <NGi :span="appStore.isMobile ? 24 : 6">
            <NSpace justify="end" class="search-actions">
              <NButton type="primary" @click="handleSearch">搜索</NButton>
              <NButton @click="handleReset">重置</NButton>
            </NSpace>
          </NGi>
        </NGrid>
      </NForm>
    </NCard>

    <NCard :bordered="false" title="菜品管理" class="dish-catalogue-card">
      <template #header-extra>
        <NSpace><NButton v-if="!appStore.isMobile" @click="viewMode = viewMode === 'cards' ? 'table' : 'cards'">{{viewMode === 'cards' ? '查看表格' : '查看卡片'}}</NButton><NButton type="primary" @click="handleAdd">新增菜品</NButton></NSpace>
      </template>
      <NDataTable
        v-if="!appStore.isMobile && viewMode === 'table'"
        remote
        :columns="columns"
        :data="data"
        :loading="loading"
        :scroll-x="1520"
        :pagination="{
          page: searchForm.pageNum,
          pageSize: searchForm.pageSize,
          itemCount: total,
          showSizePicker: true,
          showQuickJumper: true,
          prefix: ({ itemCount, pageCount }) => `共 ${itemCount} 条 / ${pageCount} 页`,
          pageSizes: [10, 20, 50, 100, 200],
          onChange: handlePageChange,
          onUpdatePageSize: handlePageSizeChange
        }"
      />
      <div v-else class="mobile-dish-cards" :aria-busy="loading">
        <p v-if="loading" class="dish-loading" role="status">正在读取菜品…</p>
        <NEmpty v-if="!loading && !data.length" description="暂无菜品" />
        <article v-for="row in data" :key="row.id" class="mobile-record-card" :data-dish-id="row.id">
          <div class="mobile-dish-heading">
            <button class="dish-picture-button" :aria-label="'编辑'+row.name+'的照片'" @click="handleEdit(row)"><img v-if="row.image" :src="row.image" :alt="row.name" loading="lazy"/><span>{{row.image?'换照片':'加照片'}}</span></button>
            <div class="mobile-dish-info"><div class="mobile-record-head"><strong>{{ row.name }}</strong><span class="mobile-record-amount">¥{{ Number(row.price || 0).toFixed(2) }}</span></div>
            <div class="mobile-record-meta">{{ row.categoryName || '未分类' }} · {{ row.status !== 1 ? '已下架' : row.soldOut === 1 ? '今天卖完了' : row.stock === 0 ? '库存为零' : '正常售卖' }}</div></div>
          </div>
          <div class="mobile-record-actions">
            <NButton @click="handleEdit(row)">编辑菜品</NButton>
            <NPopconfirm @positive-click="handleToggleSoldOut(row)"><template #trigger><NButton :type="row.soldOut === 1 ? 'success' : 'warning'">{{ row.soldOut === 1 ? '恢复售卖' : '卖完了' }}</NButton></template>{{ row.soldOut === 1 ? '确认取消卖完标记？' : '确认卖完了？' }}</NPopconfirm>
            <NPopconfirm @positive-click="handleToggleStatus(row)"><template #trigger><NButton>{{ row.status === 1 ? '下架' : '上架' }}</NButton></template>确认{{ row.status === 1 ? '下架' : '上架' }}？</NPopconfirm>
          </div>
        </article>
        <NPagination :page="searchForm.pageNum" :page-size="searchForm.pageSize" :item-count="total" simple @update:page="handlePageChange" />
      </div>
    </NCard>

    <NModal :trap-focus="!appStore.isMobile" v-model:show="showModal" preset="card" :title="isEdit ? '编辑菜品' : '新增菜品'" class="dish-edit-modal" :mask-closable="!uploadLoading && !saving" :close-on-esc="!uploadLoading && !saving" :closable="!uploadLoading && !saving" style="width: 720px;">
      <NForm ref="formRef" :disabled="saving" :model="formModel" :rules="rules" :label-placement="appStore.isMobile ? 'top' : 'left'" label-width="100">
        <NFormItem label="菜品照片">
          <DishPhotoPicker :key="photoSession" :preview-url="previewImageUrl" :disabled="saving" @busy="uploadLoading = $event" @uploaded="handleDishPhoto" />
        </NFormItem>
        <NFormItem label="所属分类">
          <NSelect v-model:value="formModel.categoryId" :options="categoryOptions" placeholder="未分类也可直接保存" @update:value="handleCategoryChange" />
        </NFormItem>
        <NFormItem label="菜品名称" path="name">
          <NInput v-model:value="formModel.name" placeholder="请输入菜品名称" />
        </NFormItem>
        <NFormItem label="价格" path="price">
          <NInputNumber v-model:value="formModel.price" :min="0" :precision="2" style="width: 100%" />
        </NFormItem>
        <NFormItem label="库存">
          <NInputNumber v-model:value="formModel.stock" :min="-1" placeholder="-1表示不限库存" style="width: 100%" />
        </NFormItem>

        <NFormItem v-if="restaurantFeatures.specs" label="菜品规格">
          <NSpace vertical style="width: 100%;">
            <div class="spec-builder">
              <NSelect v-model:value="extraSpecGroupId" :options="availableExtraSpecGroupOptions" placeholder="给当前菜品额外加一个规格组" clearable />
              <NButton :disabled="!extraSpecGroupId" @click="addExtraSpecGroup">添加规格组</NButton>
            </div>
            <div v-if="formModel.specItems?.length" class="dish-spec-list">
              <div v-for="(item, index) in formModel.specItems" :key="`${item.specGroupId}-${index}`" class="dish-spec-row">
                <div class="dish-spec-row__head">
                  <strong>{{ item.specGroupName }}</strong>
                  <NButton quaternary type="error" @click="removeSpecItem(index)">移除</NButton>
                </div>
                <NSelect
                  :value="item.optionIds"
                  multiple
                  :options="getSpecOptionSelectOptions(item.specGroupId)"
                  placeholder="请选择该菜品支持的规格值"
                  @update:value="value => updateSpecItemOptions(index, value as Api.Business.IdType[])"
                />
              </div>
            </div>
            <NTag v-else type="default">当前未配置规格，菜品将按普通单品展示</NTag>
          </NSpace>
        </NFormItem>
        <NFormItem label="简介">
          <NInput v-model:value="formModel.description" type="textarea" placeholder="请输入菜品简介" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="uploadLoading || saving" @click="showModal = false">取消</NButton>
          <NButton type="primary" :loading="saving" :disabled="uploadLoading" @click="handleSubmit">{{uploadLoading ? '照片上传中…' : '保存菜品'}}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>

<style scoped>
.mobile-dish-heading{display:flex;align-items:center;gap:14px}.mobile-dish-info{flex:1;min-width:0}
.dish-picture-button{position:relative;width:76px;height:76px;flex-shrink:0;border:0;border-radius:10px;overflow:hidden;background:var(--restaurant-photo-bg,#f2ede6);cursor:pointer;color:var(--restaurant-muted);padding:0}
.dish-picture-button img{width:100%;height:100%;object-fit:cover}.dish-picture-button span{position:absolute;inset:auto 0 0;background:#352a23bd;color:white;font-size:.6875rem;padding:3px 0;text-align:center}.dish-picture-button:not(:has(img)) span{position:static;background:none;color:inherit;font-size:.8125rem}
.dish-picture-button:focus-visible{outline:2px solid #a94d24;outline-offset:3px}

.search-actions {
  width: 100%;
}

.spec-builder {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 12px;
  width: 100%;
}

.dish-spec-list {
  display: grid;
  gap: 12px;
}

.dish-spec-row {
  padding: 14px;
  border: 1px solid #e5edf8;
  border-radius: 14px;
  background: #f8fbff;
}

.dish-spec-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
</style>
