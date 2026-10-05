import { onActivated, onDeactivated, onMounted, onUnmounted } from 'vue';
import { connectWebSocket, subscribe, type WsMessage } from '@/service/websocket';
/** Refresh only the currently viewed bill; serialize reads when several changes arrive. */
export function useOrderAdjustments(matches: (message: WsMessage) => boolean, refresh: () => Promise<void>) {
  let active=true, running=false, pending=false;
  let stop: (()=>void)|undefined;
  async function changed(){pending=true;if(running)return;running=true;try{while(pending&&active){pending=false;await refresh();}}finally{running=false;}}
  onActivated(()=>{active=true;});onDeactivated(()=>{active=false;});
  onMounted(()=>{connectWebSocket();stop=subscribe('/topic/kitchen',message=>{if(active&&['ORDER_CHANGED','NEW_ORDER'].includes(message.eventType)&&matches(message))void changed();});});
  onUnmounted(()=>stop?.());
}
