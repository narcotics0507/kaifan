import { useAuthStore } from '@/store/modules/auth';
import { localStg } from '@/utils/storage';
import type { RequestInstanceState } from './type';

export function getAuthorization() {
  const token = localStg.get('token');
  // 后端 Sa-Token 直接使用 token 值，不需要 Bearer 前缀
  return token || null;
}

/** Sa-Token has no refresh-token endpoint; expired sessions must sign in again. */
async function handleExpiredSession() {
  useAuthStore().resetStore();
  return false;
}

export async function handleExpiredRequest(state: RequestInstanceState) {
  if (!state.refreshTokenPromise) {
    state.refreshTokenPromise = handleExpiredSession();
  }

  const success = await state.refreshTokenPromise;

  setTimeout(() => {
    state.refreshTokenPromise = null;
  }, 1000);

  return success;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
