<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useOnline } from '@vueuse/core';
import { NAlert, NButton, NCard, NCheckbox, NEmpty, NInput, NInputNumber, NModal, NSpace, useMessage } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { useMerchantOverlays } from '@/hooks/business/merchant-overlays';
import ReturnedOrderItems from '@/components/business/returned-order-items.vue';
import { fetchKitchenBills, fetchKitchenPapers, fetchKitchenReceipt, shortageReturn, waiveKitchenItem, type KitchenPaper, type KitchenPaperItem } from '@/service/api';
import { connectWebSocket, subscribe } from '@/service/websocket';
import { KitchenNoticeTracker, kitchenNoticeText, compareKitchenNumbers, type KitchenNotice } from './notices';
const appStore=useAppStore(),online=useOnline(),message=useMessage();
let billRevision=0;
const bills=ref<Api.Business.Order[]>([]),keyword=ref(''),loading=ref(false),busy=ref(false),error=ref('');
const returnOpen=ref(false),waiveOpen=ref(false),paperOpen=ref(false),returnQty=ref<number|null>(1),requestId=ref(''),notifyKitchen=ref(false);
const selectedItem=ref<Api.Business.OrderItem|null>(null),selectedBill=ref<Api.Business.Order|null>(null);
const papers=ref<KitchenPaper[]>([]),receipt=ref('');
const visibleBills=computed(()=>bills.value.filter(b=>(b.tableCode+' '+b.orderNo).toLowerCase().includes(keyword.value.trim().toLowerCase())));
const pageMode=ref<'queue'|'tables'>('queue'),waiveReason=ref('做错菜，门店免单');
const paperCache=ref<Record<string,KitchenPaper[]>>({});
const queueNumber=(paper:KitchenPaper)=>String(paper.queueNumber||0).padStart(3,'0');
const numberedPapers=computed(()=>visibleBills.value.flatMap(bill=>(paperCache.value[String(bill.id)]||[]).filter(p=>p.queueNumber!=null&&['TICKET_ORDER','TICKET_ADD'].includes(p.type)).map(paper=>({bill,paper}))).sort((a,b)=>compareKitchenNumbers(a.paper,b.paper)));
const legacyBills=computed(()=>visibleBills.value.filter(b=>legacyItems(b).length>0));
function legacyItems(bill:Api.Business.Order){const numberedIds=new Set((paperCache.value[String(bill.id)]||[]).filter(p=>p.queueNumber!=null).flatMap(p=>(p.items||[]).map(i=>String(i.orderItemId))));return (bill.items||[]).filter(i=>!numberedIds.has(String(i.id)));}
function paperItem(bill:Api.Business.Order,row:KitchenPaperItem){return (bill.items||[]).find(item=>String(item.id)===String(row.orderItemId));}
function openWaive(bill:Api.Business.Order,item:Api.Business.OrderItem){selectedBill.value=bill;selectedItem.value=item;requestId.value=uuid();waiveReason.value='做错菜，门店免单';waiveOpen.value=true;}
function applyBill(data:Api.Business.Order){billRevision+=1;bills.value=bills.value.map(b=>String(b.id)===String(data.id)?data:b).filter(b=>b.status===0||b.status===1);}
async function confirmWaive(){if(busy.value||!selectedItem.value||!waiveReason.value.trim())return;busy.value=true;try{const result=await waiveKitchenItem(selectedItem.value.id,requestId.value,waiveReason.value.trim());if(result.data&&!result.error){applyBill(result.data);waiveOpen.value=false;message.success(`本菜已免单，本单应收 ¥${money(result.data.actualAmount)}`);await load();}}finally{busy.value=false;}}
const tableCount=computed(()=>new Set(bills.value.map(b=>String(b.tableId))).size);
const uuid=()=>globalThis.crypto?.randomUUID?.()||'r-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
const money=(n:number)=>Number(n||0).toFixed(2);
const voiceSupported = 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window;
const voiceReady = ref(false), voiceEnabled = ref(true), voiceError = ref(''), lastReminder = ref('');
const voicePreferenceKey = 'kaifan.kitchen.voice.enabled';
try { voiceEnabled.value = localStorage.getItem(voicePreferenceKey) !== '0'; } catch { /* Private browsing still permits this page. */ }
const noticeTracker = new KitchenNoticeTracker();
const initialBillIds = new Set<string>(), paperFingerprints = new Map<string, string>();
let establishedSnapshot = false, disposed = false;
const ownedUtterances = new Set<SpeechSynthesisUtterance>();
function saveVoicePreference() { try { localStorage.setItem(voicePreferenceKey, voiceEnabled.value ? '1' : '0'); } catch { /* Keep in-memory preference. */ } }
function speak(text: string) {
  if (!voiceSupported || !voiceEnabled.value || !voiceReady.value || disposed) return;
  const synth = window.speechSynthesis;
  const utterance = new SpeechSynthesisUtterance(text);
  const voices = synth.getVoices();
  const voice = voices.find(v => v.lang.toLowerCase() === 'zh-cn' && v.localService) || voices.find(v => v.lang.toLowerCase().startsWith('zh'));
  utterance.lang = 'zh-CN'; utterance.rate = 1; utterance.volume = 1;
  if (voice) utterance.voice = voice;
  ownedUtterances.add(utterance);
  utterance.onstart = () => { voiceError.value = ''; };
  utterance.onend = () => { ownedUtterances.delete(utterance); };
  utterance.onerror = event => {
    ownedUtterances.delete(utterance);
    if (disposed || !voiceEnabled.value || ['canceled', 'interrupted'].includes(event.error)) return;
    voiceReady.value = false;
    voiceError.value = event.error === 'not-allowed' ? '浏览器暂未允许声音，请点“开启声音”重试。' : '声音播放失败，请检查设备音量并重新试播。';
    synth.cancel(); ownedUtterances.clear();
  };
  try { synth.resume(); synth.speak(utterance); } catch { ownedUtterances.delete(utterance); voiceReady.value = false; voiceError.value = '声音播放失败，请重新试播。'; }
}
function enableVoice(test = false) {
  if (!voiceSupported) return;
  // Call synchronously from a real click, including when the voices list is still loading.
  voiceEnabled.value = true; voiceReady.value = true; voiceError.value = ''; saveVoicePreference();
  window.speechSynthesis.cancel(); ownedUtterances.clear();
  speak(test ? kitchenNoticeText({paperId:'voice-test',kind:'new',tableCode:'A03',queueNumber:1,items:[]},true) : '后厨语音提醒已开启。');
}
function toggleVoice() {
  if (!voiceEnabled.value || !voiceReady.value) { enableVoice(); return; }
  voiceEnabled.value = false; voiceReady.value = false; saveVoicePreference();
  window.speechSynthesis.cancel(); ownedUtterances.clear();
}
async function checkNewPapers(nextBills: Api.Business.Order[]) {
  if (!establishedSnapshot) { nextBills.forEach(b => initialBillIds.add(String(b.id))); establishedSnapshot = true; }
  const candidates = nextBills.filter(b => paperFingerprints.get(String(b.id)) !== JSON.stringify((b.items || []).map(i => [String(i.id), i.quantity])));
  const results = await Promise.all(candidates.map(async bill => ({ bill, response: await fetchKitchenPapers(bill.id) })));
  if (disposed) return;
  const pendingNotices: KitchenNotice[] = [];
  for (const { bill, response } of results) {
    if (response.error || !response.data) continue; // Retry on the next poll, rather than losing a notice.
    paperCache.value[String(bill.id)] = response.data;
    const baseline = initialBillIds.has(String(bill.id)) && !noticeTracker.isPrimed(bill.id);
    const notices = noticeTracker.consume(bill.id, bill.tableCode || '', response.data, baseline);
    paperFingerprints.set(String(bill.id), JSON.stringify((bill.items || []).map(i => [String(i.id), i.quantity])));
    pendingNotices.push(...notices);
  }
  pendingNotices.sort(compareKitchenNumbers).forEach(notice=>{const text=kitchenNoticeText(notice);lastReminder.value=text;message.info(text);speak(kitchenNoticeText(notice,true));});
}
async function load(){if(disposed||loading.value||!online.value)return;loading.value=true;const revision=billRevision;try{const {data,error:failure}=await fetchKitchenBills();if(!failure&&data&&revision===billRevision){bills.value=data;error.value='';await checkNewPapers(data);}else if(failure)error.value='单据更新失败，请稍后重试';}finally{loading.value=false;if(revision!==billRevision)void load();}}
function openReturn(bill:Api.Business.Order,item:Api.Business.OrderItem){selectedBill.value=bill;selectedItem.value=item;returnQty.value=1;requestId.value=uuid();notifyKitchen.value=false;returnOpen.value=true;}
async function confirmReturn(){if(busy.value||!selectedItem.value||returnQty.value==null)return;busy.value=true;try{const {data,error:failure}=await shortageReturn(selectedItem.value.id,returnQty.value,requestId.value,notifyKitchen.value);if(!failure&&data){billRevision+=1;bills.value=bills.value.map(b=>String(b.id)===String(data.id)?data:b).filter(b=>b.status===0||b.status===1);returnOpen.value=false;message.success(`缺菜已退，本单应收 ¥${money(data.actualAmount)}`);await load();}}finally{busy.value=false;}}
async function openPapers(bill:Api.Business.Order){selectedBill.value=bill;papers.value=[];receipt.value='';paperOpen.value=true;const [p,r]=await Promise.all([fetchKitchenPapers(bill.id),fetchKitchenReceipt(bill.id)]);if(p.data)papers.value=p.data;if(r.data)receipt.value=r.data;}
const paperLabel=(type:string)=>({TICKET_ORDER:'点菜单',TICKET_ADD:'加菜单',TICKET_CHANGE:'厨房变更通知',TICKET_REPRINT:'补打结账单'}[type]||'单据');
let timer:ReturnType<typeof setInterval>,stops:Array<()=>void>=[];
const changed=()=>{billRevision+=1;void load();};
onMounted(()=>{void load();connectWebSocket();stops=[subscribe('/topic/kitchen',changed),subscribe('/topic/table-status',changed),subscribe('/topic/sold-out',changed)];timer=setInterval(()=>void load(),8000);});
onUnmounted(()=>{disposed=true;clearInterval(timer);stops.forEach(stop=>stop());if(voiceSupported&&ownedUtterances.size)window.speechSynthesis.cancel();ownedUtterances.clear();});
useMerchantOverlays(returnOpen,waiveOpen,paperOpen);
</script>
<template>
  <div class="kitchen-papers-page">
    <NCard :bordered="false"><div class="paper-page-head"><div><h2>厨房单据</h2><p>每天北京时间从 001 排起，加菜另排新号；跨天单据按日期区分。</p></div><strong>{{tableCount}} 桌 · {{pageMode==='queue'?numberedPapers.length+legacyBills.length:bills.length}} {{pageMode==='queue'?'批点单':'笔账单'}}</strong></div></NCard>
    <section class="kitchen-voice-panel" aria-label="新单和加菜语音提醒">
      <div class="voice-controls"><strong>新单 / 加菜语音</strong><span class="voice-state">{{!voiceSupported?'当前浏览器不支持语音':voiceEnabled&&voiceReady?'已开启':voiceEnabled?'待开启声音':'已关闭'}}</span><NButton type="primary" :disabled="!voiceSupported" @click="toggleVoice">{{voiceEnabled&&voiceReady?'关闭声音':'开启声音'}}</NButton><NButton :disabled="!voiceSupported" @click="enableVoice(true)">试播</NButton></div>
      <p>营业时保持页面打开、屏幕亮着，先点“试播”确认有声音。</p>
      <NAlert v-if="voiceError" type="warning">{{voiceError}}</NAlert>
      <p v-if="lastReminder" class="latest-reminder" role="status">最新提醒：{{lastReminder}}</p>
    </section>
    <NAlert v-if="!online" type="warning">网络已断开，当前是上次加载的单据。</NAlert>
    <NAlert v-if="error" type="warning">{{error}}</NAlert>
    <div class="paper-filter"><NInput v-model:value="keyword" placeholder="搜索桌号或订单号" clearable/><NButton :loading="loading" @click="load">刷新</NButton></div>
    <div class="queue-switch"><NButton :type="pageMode==='queue'?'primary':'default'" @click="pageMode='queue'">按厨房顺序</NButton><NButton :type="pageMode==='tables'?'primary':'default'" @click="pageMode='tables'">按桌看账单</NButton></div>
    <details class="kitchen-help"><summary>看单说明 · 打印机待接入</summary><p class="paper-help">序号表示下单先后，不表示还要等待几桌。加菜有新号，但仍和原桌合账；结账或结束用餐后归档。旧单继续按原下单时间在前面。缺菜退掉，做错菜可免单。</p><p class="paper-help">实体打印机尚未接入，现在显示电子单据。语音只提醒新单和加菜，打开页面不会重读旧单。试播内容：A零三号桌，顺序号零零一。语音版本：20261004-2。</p></details>
    <NEmpty v-if="!visibleBills.length && !loading" description="当前没有用餐中的单据，已结束用餐的记录已归档"/>
    <NCard v-for="bill in (pageMode==='tables'?visibleBills:legacyBills)" :key="bill.id" :data-order-id="bill.id" class="kitchen-bill" :bordered="true">
      <div class="bill-heading"><strong>{{bill.tableCode}} 桌{{pageMode==='queue'?' · 原有点单（未编号）':''}}</strong><span>{{bill.areaName}}</span><NButton size="small" @click="openPapers(bill)">查看小票</NButton></div>
      <p class="bill-meta">{{bill.orderNo}} · {{bill.createTime?.replace('T',' ')}}</p>
      <p class="bill-meta">{{bill.status===0 ? '已下单 · 本单待收 ¥'+money(Math.max(0,bill.actualAmount-(bill.paidAmount||0))) : Number(bill.actualAmount)===0 ? '本单已免单，不收费' : '本单已结清，仍保留在本次用餐中'}}</p>
      <article v-for="item in (pageMode==='tables'?bill.items:legacyItems(bill))" :key="item.id" :data-item-id="item.id" class="bill-food">
        <div><strong>{{item.dishName}} × {{item.quantity}}</strong><small v-if="item.remark">口味：{{item.remark}}</small><small v-if="item.soldOut===1">已停止新点单，这条已下单记录仍保留</small><small v-if="item.isGift===1">本菜已免单，不收费</small></div>
        <div class="food-actions"><NButton type="warning" :disabled="busy||!bill.shortageAllowed" @click="openReturn(bill,item)">{{bill.shortageAllowed?'缺菜退掉':'已收款'}}</NButton><NButton type="info" :disabled="busy||!bill.shortageAllowed||bill.status!==0||item.isGift===1" @click="openWaive(bill,item)">{{item.isGift===1?'已免单':'这道菜免单'}}</NButton></div>
      </article>
      <ReturnedOrderItems :items="bill.returnedItems"/>
    </NCard>
    <template v-if="pageMode==='queue'">
      <NCard v-for="entry in numberedPapers" :key="entry.paper.id" class="kitchen-batch" :data-paper-id="entry.paper.id">
        <div class="bill-heading"><strong class="queue-badge">{{queueNumber(entry.paper)}}号</strong><strong>{{entry.bill.tableCode}} 桌 · {{entry.paper.type==='TICKET_ADD'?'加菜':'新单'}}</strong><NButton size="small" @click="openPapers(entry.bill)">查看小票</NButton></div>
        <p class="bill-meta">{{entry.paper.queueDate}} · {{entry.paper.createTime?.replace('T',' ')}} · 只列本次菜品</p>
        <article v-for="(row,index) in entry.paper.items||[]" :key="row.orderItemId||index" class="bill-food" :data-item-id="row.orderItemId">
          <div><strong>{{row.dishName}} × {{paperItem(entry.bill,row)?.quantity||0}}</strong><small v-if="row.remark">口味：{{row.remark}}</small><small v-if="!paperItem(entry.bill,row)">已退掉，不再制作、不收费</small><small v-else-if="paperItem(entry.bill,row)?.isGift===1">本菜已免单，不收费</small><small v-else-if="paperItem(entry.bill,row)?.quantity!==row.quantity">原小票 {{row.quantity}} 份，以当前剩余数量为准</small></div>
          <div class="food-actions" v-if="paperItem(entry.bill,row)"><NButton type="warning" :disabled="busy||!entry.bill.shortageAllowed" @click="openReturn(entry.bill,paperItem(entry.bill,row)!)">缺菜退掉</NButton><NButton type="info" :disabled="busy||!entry.bill.shortageAllowed||entry.bill.status!==0||paperItem(entry.bill,row)?.isGift===1" @click="openWaive(entry.bill,paperItem(entry.bill,row)!)">{{paperItem(entry.bill,row)?.isGift===1?'已免单':'这道菜免单'}}</NButton></div>
        </article>
        <p class="paper-help" v-if="!(entry.paper.items||[]).length">该旧单据没有结构化菜品，可切换“按桌看账单”查看。</p>
      </NCard>
    </template>

    <NModal v-model:show="waiveOpen" preset="card" title="确认这道菜免单" :auto-focus="false" :trap-focus="!appStore.isMobile" :mask-closable="!busy" style="width:440px">
      <p><strong>{{selectedBill?.tableCode}} 桌 · {{selectedItem?.dishName}} × {{selectedItem?.quantity}} 份</strong></p>
      <p>本行全部免单，原金额 ¥{{money(selectedItem?.amount||0)}}；其他菜仍照常收费。菜品保留为“免单、不收费”，已做的菜无需再做一次。</p>
      <label>免单原因<NInput v-model:value="waiveReason" maxlength="150" :disabled="busy" placeholder="例如：做错菜，门店免单"/></label>
      <NSpace justify="end"><NButton :disabled="busy" @click="waiveOpen=false">取消</NButton><NButton type="primary" :loading="busy" :disabled="!waiveReason.trim()" @click="confirmWaive">确认本菜免单</NButton></NSpace>
    </NModal>
    <NModal v-model:show="returnOpen" preset="card" title="缺菜退掉" :auto-focus="false" :trap-focus="!appStore.isMobile" :mask-closable="!busy" style="width:440px">
      <p><b>{{selectedBill?.tableCode}} 桌 · {{selectedItem?.dishName}}</b></p><p>本行剩余 {{selectedItem?.quantity}} 份。只退确认无法供应、尚未端上桌的数量。</p>
      <label>退几份<NInputNumber v-model:value="returnQty" :min="1" :max="selectedItem?.quantity" :precision="0" :disabled="busy" aria-label="退菜份数"/></label>
      <NButton v-if="selectedItem" quaternary :disabled="busy" @click="returnQty=selectedItem.quantity">退全部 {{selectedItem.quantity}} 份</NButton>
      <p class="paper-help">这几份从账单扣除，同时停止这道菜的新点单。在厨房本人操作时，在原纸单上划掉即可。</p><NCheckbox v-model:checked="notifyKitchen" :disabled="busy">需要通知厨房（前台代操作请勾选）</NCheckbox><p v-if="notifyKitchen" class="paper-help">生成一张单独的退菜通知；实体打印仍需接通设备。</p>
      <NSpace justify="end"><NButton :disabled="busy" @click="returnOpen=false">取消</NButton><NButton type="warning" :loading="busy" :disabled="returnQty==null||!selectedItem||returnQty<1||returnQty>selectedItem.quantity" @click="confirmReturn">确认退菜并停售</NButton></NSpace>
    </NModal>
    <NModal v-model:show="paperOpen" preset="card" title="电子小票" :trap-focus="!appStore.isMobile" style="width:560px">
      <p class="paper-help">以下是保存的单据内容，不表示已从实体打印机出票。补打与结账应使用最新账单，不能照旧单重复做菜。</p>
      <h3>最新结账单</h3><pre class="paper-preview">{{receipt||'正在读取…'}}</pre>
      <h3>原点单及变更记录</h3><NEmpty v-if="!papers.length" description="暂无小票记录（旧订单仍可查看上方最新账单）"/>
      <section v-for="paper in papers" :key="paper.id" class="paper-record"><b>{{paper.queueNumber!=null?queueNumber(paper)+'号 · ':''}}{{paperLabel(paper.type)}}</b><small>待接入打印机 · {{paper.createTime?.replace('T',' ')}}</small><pre class="paper-preview">{{paper.text}}</pre></section>
    </NModal>
  </div>
</template>
<style scoped>
.kitchen-help{color:var(--restaurant-muted);font-size:.8125rem}.kitchen-help summary{min-height:44px;display:flex;align-items:center;cursor:pointer;list-style:revert}.kitchen-help summary:focus-visible{outline:2px solid #a94d24;outline-offset:2px}.kitchen-help[open]{padding-bottom:10px;border-bottom:1px solid var(--restaurant-line)}
.kitchen-papers-page{display:grid;gap:12px;min-width:0}.paper-page-head,.bill-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.paper-page-head h2{margin:0;font-size:1.375rem}.paper-page-head p,.bill-meta,.paper-help{font-size:.8125rem;line-height:1.7;color:var(--restaurant-muted);overflow-wrap:anywhere}.paper-page-head p{margin:6px 0 0}.paper-filter{display:flex;gap:10px}.paper-filter>.n-input{flex:1;min-width:0}.bill-heading>strong{font-size:1.125rem}.bill-heading>span{flex:1;color:var(--restaurant-muted)}.bill-food{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 0;border-bottom:1px solid var(--restaurant-line);flex-wrap:wrap}.bill-food>div{flex:1;min-width:8em;overflow-wrap:anywhere}.bill-food strong{font-size:1rem;line-height:1.6}.bill-food small{display:block;font-size:.8125rem;line-height:1.6;color:var(--restaurant-muted)}.bill-food>.n-button{min-height:44px}.paper-preview{font:inherit;font-size:.875rem;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:#f5eee5;border:1px solid #e7daca;border-radius:10px;margin:10px 0}.paper-record small{display:block;font-size:.75rem;color:var(--restaurant-muted)}label{display:block;margin:12px 0;line-height:1.8}
.kitchen-voice-panel{padding:14px;border:1px solid var(--restaurant-line);border-radius:12px;background:var(--restaurant-paper);min-width:0}.voice-controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.voice-controls strong{font-size:1rem}.voice-state{font-size:.8125rem;color:var(--restaurant-muted);margin-right:auto}.kitchen-voice-panel p{font-size:.8125rem;color:var(--restaurant-muted);line-height:1.7;overflow-wrap:anywhere;margin:10px 0 0}.voice-controls .n-button{min-height:44px}.kitchen-voice-panel .latest-reminder{color:#a94d24}
.food-actions{display:flex!important;flex:none!important;gap:8px;flex-wrap:wrap}.food-actions .n-button{min-height:44px}.queue-switch{display:flex;gap:8px;flex-wrap:wrap}.queue-switch .n-button{min-height:44px}.queue-badge{font-size:1.75rem!important;color:#a94d24;line-height:1.2}.kitchen-batch{border-left:3px solid #bd6a3e}
</style>
