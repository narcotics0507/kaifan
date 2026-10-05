import { onDeactivated, watch } from 'vue';
import type { Ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { useAppStore } from '@/store/modules/app';

/** Close business sheets on tab navigation, including a tap on the current tab. */
export function useMerchantOverlays(...overlays: Ref<boolean>[]) {
  const appStore = useAppStore();
  const dismiss = () => overlays.forEach(show => { show.value = false; });
  watch(() => appStore.merchantNavigationVersion, dismiss, { flush: 'sync' });
  onBeforeRouteLeave(dismiss);
  onDeactivated(dismiss);
}
