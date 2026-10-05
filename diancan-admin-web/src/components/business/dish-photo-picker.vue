<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { NButton } from 'naive-ui';
import { uploadDishImage } from '@/service/api';
import { prepareDishPhoto } from '@/utils/dish-photo';

const props = defineProps<{ previewUrl: string; disabled?: boolean }>();
const emit = defineEmits<{ uploaded: [result: Api.Business.FileUploadResult]; busy: [value: boolean] }>();
const camera = ref<HTMLInputElement | null>(null), album = ref<HTMLInputElement | null>(null);
const busy = ref(false), progress = ref(''), error = ref(''), localPreview = ref('');
const preview = computed(() => localPreview.value || props.previewUrl);
let disposed = false;
function clearPreview() { if (localPreview.value) URL.revokeObjectURL(localPreview.value); localPreview.value = ''; }
async function selectPhoto(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || busy.value || props.disabled) return;
  busy.value = true; emit('busy', true); error.value = ''; progress.value = '正在处理照片…';
  try {
    const photo = await prepareDishPhoto(file);
    if (disposed) return;
    clearPreview(); localPreview.value = URL.createObjectURL(photo); progress.value = '正在上传照片…';
    const { data, error: failure } = await uploadDishImage(photo);
    if (disposed) return;
    if (failure || !data) throw new Error('照片上传失败，请检查网络后重新选择。');
    emit('uploaded', data);
    progress.value = '照片已上传，点击下方保存后生效。';
  } catch (failure) {
    if (disposed) return;
    clearPreview(); progress.value = '';
    error.value = failure instanceof Error ? failure.message : '照片上传失败，请重试。';
  } finally {
    busy.value = false;
    if (!disposed) emit('busy', false);
  }
}
onUnmounted(() => { disposed = true; clearPreview(); emit('busy', false); });
</script>

<template>
  <div class="dish-photo-picker" :aria-busy="busy">
    <div class="photo-preview">
      <img v-if="preview" :src="preview" alt="待保存的照片" />
      <span v-else>添加一张照片</span>
    </div>
    <div class="photo-controls">
      <strong>{{ preview ? '换一张更好看的照片' : '上传实拍照片' }}</strong>
      <p>照片会自动缩小，上传后请记得保存。</p>
      <div class="photo-buttons">
        <NButton type="primary" :disabled="busy || disabled" @click="camera?.click()">拍照</NButton>
        <NButton :disabled="busy || disabled" @click="album?.click()">从相册选择</NButton>
      </div>
      <p v-if="progress" role="status" class="photo-progress">{{ progress }}</p>
      <p v-if="error" role="alert" class="photo-error">{{ error }}</p>
    </div>
    <input ref="camera" class="photo-input" type="file" accept="image/*" capture="environment" aria-label="拍摄菜品照片" @change="selectPhoto" />
    <input ref="album" class="photo-input" type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" aria-label="从相册选择菜品照片" @change="selectPhoto" />
  </div>
</template>

<style scoped>
.dish-photo-picker{display:grid;grid-template-columns:120px minmax(0,1fr);gap:16px;width:100%;align-items:center}
.photo-preview{aspect-ratio:1;border-radius:12px;overflow:hidden;background:var(--restaurant-photo-bg,#f2ede6);display:grid;place-items:center}
.photo-preview img{width:100%;height:100%;object-fit:cover}.photo-preview span{padding:16px;text-align:center;font-size:.8125rem;color:var(--restaurant-muted,#796252)}
.photo-controls{min-width:0}.photo-controls strong{font-size:.9375rem}.photo-controls p{font-size:.8125rem;line-height:1.6;color:var(--restaurant-muted,#796252);margin:6px 0 10px;overflow-wrap:anywhere}
.photo-buttons{display:flex;gap:8px;flex-wrap:wrap}.photo-buttons .n-button{min-height:44px}.photo-controls .photo-progress{color:#337051}.photo-controls .photo-error{color:#b33b31}.photo-input{display:none}
@media(max-width:480px){.dish-photo-picker{grid-template-columns:88px minmax(0,1fr);gap:12px}.photo-buttons{flex-direction:column;align-items:stretch}.photo-controls strong{font-size:.875rem}}
</style>
