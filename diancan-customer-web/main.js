import {createApp,ref,computed,onMounted,onUnmounted,nextTick,watch} from 'vue/dist/vue.esm-bundler.js';
import './style.css';
import './refinement.css';
import {installCustomerViewport} from './viewport.js';
const manager=location.pathname.includes('/tables');
let token='';
const imageUrl=u=>String(u||'').replace('http://127.0.0.1:19000/','/__images/');
async function api(path,method='GET',data){
  let response;
  try{response=await fetch('/api'+path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json',...(token?{Authorization:token}:{})},body:data===undefined?undefined:JSON.stringify(data),signal:AbortSignal.timeout?AbortSignal.timeout(12000):undefined});}
  catch(e){throw Object.assign(new Error('网络连接中断，请检查网络后重试'),{network:true});}
  let payload;try{payload=await response.json();}catch{throw new Error('点餐服务暂时不可用，请稍后重试');}
  if(!response.ok||payload.code!==200)throw Object.assign(new Error(payload.message||'操作未完成，请重试'),{code:payload.code||response.status});
  return payload.data;
}
function uuid(){return globalThis.crypto?.randomUUID?.()||'r'+Date.now().toString(16)+'-'+Math.random().toString(16).slice(2)+'-'+Math.random().toString(16).slice(2);}
const storage={get(k){try{return JSON.parse(sessionStorage.getItem('kaifan.h5.'+k)||'null');}catch{return null;}},set(k,v){try{sessionStorage.setItem('kaifan.h5.'+k,JSON.stringify(v));}catch{}},remove(k){try{sessionStorage.removeItem('kaifan.h5.'+k);}catch{}}};
const supportPhone=String(import.meta.env.VITE_SUPPORT_PHONE||'').replace(/[^0-9+]/g,'');
const OrderingHelp={setup(){return {supportPhone};},template:`<aside v-if="supportPhone" class="support-note" aria-label="点餐帮助"><details class="support-details"><summary><span>点餐遇到问题？</span><span class="support-contact">联系店主儿子<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></span></summary><div class="support-expanded"><p>小店由两位长辈经营。如遇点餐页面问题，可联系店主的儿子协助处理。</p><a :href="'tel:'+supportPhone" :aria-label="'拨打店主儿子的电话'+supportPhone"><svg class="support-phone-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 3H5a2 2 0 0 0-2 2c0 8.8 7.2 16 16 16a2 2 0 0 0 2-2v-3l-5-2-2 2a13 13 0 0 1-6-6l2-2z"/></svg><span class="support-phone-number">{{supportPhone}}</span><span class="support-call-label">拨打</span></a></div></details></aside>`};
createApp({
 components:{OrderingHelp},
 setup(){
  const restoreViewport = manager ? () => {} : installCustomerViewport();
  const categories=ref([]),menu=ref({}),cart=ref({items:[],totalCount:0,totalPrice:0}),orders=ref([]),table=ref(null);
  const selected=ref(''),query=ref(''),view=ref('menu'),busy=ref(false),loading=ref(true),online=ref(navigator.onLine),error=ref(''),notice=ref(''),ended=ref(false),invalid=ref(false);
  const modal=ref(null),detail=ref(null),detailQty=ref(1),detailRemark=ref(''),orderRemark=ref(''),lastBill=ref(null);
  const guestCount=ref(null);
  const needsGuestCount=computed(()=>!orders.value.some(o=>o.status===0||o.status===1));
  const cartPayable=computed(()=>Number(cart.value.totalPrice||0)+(needsGuestCount.value?Number(guestCount.value||0):0));
  function setGuestCount(value){guestCount.value=value===''?null:Number(value);storage.remove('submit');storage.set('guest-count',{session:table.value?.currentSessionCode,count:guestCount.value});}
  watch(()=>table.value?.currentSessionCode,session=>{const saved=storage.get('guest-count');guestCount.value=saved?.session===session?saved.count:null;});
  const managerUser=ref(''),managerPassword=ref(''),links=ref([]),qrUrls=ref({});
  const scan=new URLSearchParams(location.search);const scanCode=scan.get('table')||'';const scanKey=scan.get('key')||'';
  let timer,refreshing=false,noticeTimer,lastFocus;
  watch(modal,async value=>{if(value){lastFocus=document.activeElement;document.body.style.overflow='hidden';await nextTick();document.querySelector('.sheet')?.focus({preventScroll:true});}else{document.body.style.overflow='';lastFocus?.focus?.({preventScroll:true});}});
  const onKey=event=>{if(!modal.value)return;if(event.key==='Escape'&&!busy.value){modal.value=null;return;}if(event.key==='Tab'){const list=[...document.querySelectorAll('.sheet button:not(:disabled),.sheet input,.sheet textarea,.sheet a')].filter(el=>el.offsetParent!==null);if(!list.length)return;const first=list[0],last=list[list.length-1];if(event.shiftKey&&(document.activeElement===first||document.activeElement===document.querySelector('.sheet'))){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}};
  const money=v=>Number(v||0).toFixed(2);
  const dishes=computed(()=>{
   const all=Object.values(menu.value).flat();
   if(query.value.trim()){const q=query.value.trim().toLowerCase();return all.filter(d=>(d.name+' '+(d.description||'')+' '+(d.ingredients||'')).toLowerCase().includes(q));}
   return menu.value[selected.value]||[];
  });
  const qty=id=>cart.value.items.find(i=>String(i.dishId)===String(id))?.quantity||0;
  const pendingTotal=computed(()=>orders.value.filter(o=>o.status===0).reduce((n,o)=>n+Number(o.actualAmount||0)-Number(o.paidAmount||0),0));
  const pendingCount=computed(()=>orders.value.reduce((n,o)=>n+(o.items||[]).reduce((s,i)=>s+i.quantity,0),0));
  const active=computed(()=>!!table.value&&!ended.value&&!invalid.value);
  function toast(t){notice.value=t;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>notice.value='',3000);}
  function endVisit(){guestCount.value=null;storage.remove('guest-count');lastBill.value=orders.value.length?orders.value:lastBill.value;ended.value=true;cart.value={items:[],totalCount:0,totalPrice:0};orders.value=[];modal.value=null;storage.remove('submit');}
  async function catalogue(){const [c,m]=await Promise.all([api('/app/dish/category/list'),api('/app/dish/list')]);categories.value=c;menu.value=m;if(!selected.value||!m[selected.value])selected.value=String(c[0]?.id||'');}
  async function refresh(){if(refreshing||manager||loading.value||document.hidden)return;refreshing=true;try{
   const context=await api('/app/h5/context');if(!context.active){if(table.value)endVisit();return;}
   const previous=table.value;table.value=context.table;
   if(previous&&previous.currentSessionCode!==table.value.currentSessionCode){endVisit();return;}
   const [c,o]=await Promise.all([api('/app/cart?tableId='+table.value.id),api('/app/order/table/'+table.value.id)]);if(!(modal.value==='cart'&&document.activeElement?.tagName==='INPUT'))cart.value=c;orders.value=o;error.value='';online.value=true;
  }catch(e){if(e.code===403){endVisit();}else{error.value=e.message;if(e.network)online.value=false;}}finally{refreshing=false;}}
  async function join(){if(busy.value)return;busy.value=true;error.value='';try{
   const t=await api('/app/h5/join','POST',{tableCode:scanCode,key:scanKey});table.value=t;ended.value=false;invalid.value=false;
   storage.set('visit',{code:scanCode,session:t.currentSessionCode});view.value='menu';modal.value=null;
   const [c,o]=await Promise.all([api('/app/cart?tableId='+t.id),api('/app/order/table/'+t.id)]);cart.value=c;orders.value=o;
  }catch(e){error.value=e.message;invalid.value=!table.value;}finally{busy.value=false;}}
  async function boot(){loading.value=true;try{
   const session=await api('/app/h5/session','POST');token=session.token;
   await catalogue();
   const context=await api('/app/h5/context');
   const previous=storage.get('visit');
   if(context.active&&(!scanCode||context.table.code===scanCode)){table.value=context.table;ended.value=false;}
   else if(scanCode&&scanKey&&(!previous||previous.code!==scanCode)){await join();}
   else if(previous||context.active){ended.value=true;}
   else if(scanCode||scanKey){invalid.value=true;error.value='桌台链接不完整，请扫描桌面二维码';}
   if(table.value){const [c,o]=await Promise.all([api('/app/cart?tableId='+table.value.id),api('/app/order/table/'+table.value.id)]);cart.value=c;orders.value=o;}
  }catch(e){error.value=e.message;}finally{loading.value=false;}}
  function requireTable(){modal.value='scan';}
  function openDetail(d){detail.value=d;detailQty.value=1;detailRemark.value='';modal.value='detail';}
  async function change(d,delta){if(!active.value){requireTable();return;}if(busy.value)return;
   busy.value=true;try{const current=qty(d.id||d.dishId);if(delta>0)cart.value=await api('/app/cart/item?tableId='+table.value.id,'POST',{dishId:d.id||d.dishId,quantity:delta,remark:''});
    else if(current+delta<=0){await api('/app/cart/item/'+(d.id||d.dishId)+'?tableId='+table.value.id,'DELETE');cart.value=await api('/app/cart?tableId='+table.value.id);}
    else cart.value=await api('/app/cart/item/'+(d.id||d.dishId)+'?tableId='+table.value.id+'&quantity='+(current+delta),'PUT');
    storage.remove('submit');
   }catch(e){error.value=e.message;if(e.code===403)endVisit();}finally{busy.value=false;}}
  async function addDetail(){if(!active.value){requireTable();return;}busy.value=true;try{
   cart.value=await api('/app/cart/item?tableId='+table.value.id,'POST',{dishId:detail.value.id,quantity:detailQty.value,remark:detailRemark.value.trim()});modal.value=null;toast('已加入购物车');storage.remove('submit');
  }catch(e){error.value=e.message;if(e.code===403)endVisit();}finally{busy.value=false;}}
  async function updateRemark(item,event){busy.value=true;try{cart.value=await api('/app/cart/item/'+item.dishId+'?tableId='+table.value.id+'&remark='+encodeURIComponent(event.target.value.trim()),'PUT');storage.remove('submit');}catch(e){error.value=e.message;}finally{busy.value=false;}}
  async function clearCart(){busy.value=true;try{await api('/app/cart?tableId='+table.value.id,'DELETE');cart.value={items:[],totalCount:0,totalPrice:0};storage.remove('submit');}catch(e){error.value=e.message;}finally{busy.value=false;}}
  async function submit(){if(busy.value||!active.value||!cart.value.items.length||cart.value.hasUnavailableItems)return;if(needsGuestCount.value&&(!Number.isInteger(guestCount.value)||guestCount.value<1||guestCount.value>99)){error.value='请确认本桌用餐人数（1至99人）';return;}busy.value=true;error.value='';
   try{
    let body=storage.get('submit');if(!body||body.tableId!==table.value.id||body.sessionCode!==table.value.currentSessionCode){body={tableId:table.value.id,sessionCode:table.value.currentSessionCode,requestId:uuid(),remark:orderRemark.value.trim(),guestCount:needsGuestCount.value?guestCount.value:undefined};storage.set('submit',body);}
    await api('/app/h5/submit','POST',body);storage.remove('submit');cart.value={items:[],totalCount:0,totalPrice:0};orderRemark.value='';modal.value=null;view.value='orders';toast('下单成功，用餐后到前台结账');await refresh();
   }catch(e){error.value=e.message;if(e.code===403)endVisit();else if(!e.network)storage.remove('submit');}finally{busy.value=false;}}
  async function managerLogin(){busy.value=true;error.value='';try{token=(await api('/auth/login','POST',{username:managerUser.value,password:managerPassword.value})).token;managerPassword.value='';links.value=await api('/admin/h5/table-links');
   for(const l of links.value){const res=await fetch('/api/admin/h5/table/'+l.id+'/qrcode',{headers:{Authorization:token}});if(!res.ok||!res.headers.get('Content-Type')?.startsWith('image/'))throw new Error('桌台二维码读取失败，请重新登录刷新');qrUrls.value[l.id]=URL.createObjectURL(await res.blob());}
  }catch(e){error.value=e.message;}finally{busy.value=false;}}
  async function copyLink(url){try{await navigator.clipboard.writeText(url);toast('桌台链接已复制');}catch{modal.value='link';detail.value={url};}}
  const choose=id=>{selected.value=String(id);query.value='';document.querySelector('.dish-scroll')?.scrollTo({top:0,behavior:'auto'});};
  const onOnline=()=>{online.value=true;refresh();catalogue().catch(()=>{});};const onOffline=()=>{online.value=false;};const onVisible=()=>{if(!document.hidden){refresh();catalogue().catch(()=>{});}};
  onMounted(async()=>{if(!manager)await boot();else loading.value=false;timer=setInterval(()=>{refresh();if(!document.hidden&&!manager)catalogue().catch(()=>{});},15000);window.addEventListener('online',onOnline);window.addEventListener('offline',onOffline);document.addEventListener('visibilitychange',onVisible);document.addEventListener('keydown',onKey);});
  onUnmounted(()=>{restoreViewport();clearInterval(timer);clearTimeout(noticeTimer);window.removeEventListener('online',onOnline);window.removeEventListener('offline',onOffline);document.removeEventListener('visibilitychange',onVisible);document.removeEventListener('keydown',onKey);document.body.style.overflow='';Object.values(qrUrls.value).forEach(URL.revokeObjectURL);});
  return {manager,managerUser,managerPassword,managerLogin,printCodes:()=>window.print(),catalogue,links,qrUrls,copyLink,categories,dishes,menu,cart,orders,table,selected,query,view,busy,loading,online,error,notice,ended,invalid,modal,detail,detailQty,detailRemark,orderRemark,lastBill,guestCount,needsGuestCount,cartPayable,setGuestCount,money,qty,pendingTotal,pendingCount,active,imageUrl,choose,requireTable,openDetail,change,addDetail,updateRemark,submit,clearCart,join,boot,refresh,scanCode,scanKey};
 },
 template:`
 <div class="app-shell" :class="{manager}">
  <header class="brand-header"><img src="/order/brand.png" alt="灶边有味" class="brand-mark"><div><h1>灶边有味</h1><p>{{manager?'桌台二维码管理':'畔水居35号 · 用餐后结账'}}</p></div><span class="table-chip" v-if="active">{{table.code}} 桌</span></header>
  <div class="network-note" role="status" v-if="!online">网络已断开，已点菜品会保留。恢复网络后自动更新。</div>
  <div class="error-note" role="alert" v-if="error"><span>{{error}}</span><button @click="error='';manager?null:boot()" :disabled="busy">重试</button></div>
  <div class="toast" role="status" v-if="notice">{{notice}}</div>
  <main v-if="manager" class="manager-main">
   <section v-if="!links.length" class="manager-login"><h2>领取每桌点餐码</h2><p>用商家账号登录，下载桌台码或复制链接。</p><form @submit.prevent="managerLogin"><label>账号<input v-model="managerUser" autocomplete="username" required></label><label>密码<input v-model="managerPassword" type="password" autocomplete="current-password" required></label><button class="primary" :disabled="busy">{{busy?'登录中…':'登录并查看桌台码'}}</button></form></section>
   <template v-else><p class="manager-help">把对应二维码放在桌上。测试时可以将链接发给朋友，共同点同一桌。</p><button class="print-btn" @click="printCodes">打印桌台码</button><div class="qr-grid"><article v-for="l in links" :key="l.id" class="qr-card"><img :src="qrUrls[l.id]" :alt="l.code+'桌点餐二维码'"><h2>{{l.code}} · {{l.name}}</h2><p>微信扫一扫 · 直接点菜</p><div class="qr-actions"><a :href="qrUrls[l.id]" :download="l.code+'-点餐码.png'">下载二维码</a><button @click="copyLink(l.url)">复制链接</button><a :href="l.url" target="_blank" rel="noreferrer">打开菜单</a></div></article></div></template>
  </main>
  <template v-else>
   <div class="loading-state" v-if="loading" role="status">正在准备菜单…</div>
   <section v-if="ended" class="visit-ended"><h2>本次用餐已结束</h2><p>感谢光临。旧购物车已清空；再次入座请重新扫描桌面二维码。</p><button v-if="scanCode&&scanKey" class="primary" @click="modal='rejoin'">再次入座点餐</button><button @click="view='menu'">继续浏览菜单</button></section>
   <div class="scan-hint" v-else-if="!active&&!loading">扫描桌面二维码即可点菜，现在可以先看看菜单。</div>
   <section v-show="view==='menu'" class="menu-view">
    <div class="search-row"><label><img class="ui-icon" src="/order/icons/search.svg" alt="" aria-hidden="true"><input v-model="query" type="search" placeholder="想吃点什么？搜索菜品" aria-label="搜索菜品"></label><button @click="catalogue" title="刷新菜单">刷新</button></div>
    <div class="menu-grid"><nav class="category-list" aria-label="菜品分类"><button v-for="c in categories" :key="c.id" :class="{selected:String(c.id)===selected&&!query}" @click="choose(c.id)">{{c.name}}<small>{{(menu[c.id]||[]).length}} 道</small></button></nav>
     <div class="dish-scroll"><h2 class="section-heading">{{query?'搜索结果':categories.find(c=>String(c.id)===selected)?.name||'今日菜单'}}<small>{{dishes.length}} 道家常味</small></h2>
      <article v-for="d in dishes" :key="d.id" class="dish-row"><button class="dish-photo" @click="openDetail(d)" :aria-label="'查看'+d.name"><img :src="imageUrl(d.image||d.thumbnail)" :alt="d.name" loading="lazy" decoding="async" @load="$event.target.style.visibility='visible'" @error="$event.target.style.visibility='hidden'"></button><div class="dish-copy"><button class="dish-title" @click="openDetail(d)">{{d.name}}</button><p>{{d.description||(['不辣','微辣','中辣','重辣'][d.spiceLevel||0])}}</p><div class="dish-bottom"><strong><small>¥</small>{{money(d.price)}}<em>/份</em></strong><div class="stepper"><button v-if="qty(d.id)" @click="change(d,-1)" :disabled="busy" :aria-label="'减少'+d.name">−</button><span v-if="qty(d.id)">{{qty(d.id)}}</span><button class="plus" :class="{'needs-table':!active}" @click="change(d,1)" :disabled="busy||loading" :aria-label="'添加'+d.name">+</button></div></div></div></article>
      <p v-if="!dishes.length&&!loading" class="empty-state">暂时没有这些菜，换个关键词看看。</p><p class="menu-tail">现点现做，热乎上桌。</p><OrderingHelp/>
     </div>
    </div>
   </section>
   <section v-if="view==='orders'" class="orders-view"><div class="orders-head"><h2>本桌已点</h2><button @click="refresh" :disabled="busy">刷新账单</button></div><p class="order-summary" v-if="active">{{pendingCount}} 份菜品 <strong>待结账 ¥{{money(pendingTotal)}}</strong></p><p class="plain-hint">同桌加菜一起算，用餐后请到前台结账。</p>
    <article class="bill" v-for="o in orders" :key="o.id"><div class="bill-top"><span>{{o.status===0?'已下单 · 待结账':o.status===1?(Number(o.actualAmount)===0?'本单免单 · 不收费':'已结账'):'已取消 · 不收费'}}</span><small>{{o.orderNo}}</small></div><div class="bill-line" v-for="i in o.items" :key="i.id"><div><b>{{i.dishName}}</b><small v-if="i.remark">{{i.remark}}</small><small v-if="i.isGift">本菜免单，不收费</small></div><span>×{{i.quantity}}</span><span>{{i.isGift?'免单':'¥'+money(i.amount)}}</span></div><div v-for="r in o.returnedItems||[]" :key="r.id" class="returned-bill-line"><span>{{r.dishName}} × {{r.quantity}}<small>{{r.reason||'已退菜'}} · 不收费</small></span><b>¥0.00</b></div><div v-if="o.guestCount||o.tablewareQuantity" class="bill-line tableware-bill"><div><b>一次性餐具</b><small>本桌{{o.guestCount}}人 · ¥{{money(o.tablewareUnitPrice)}}/套</small></div><span>×{{o.tablewareQuantity}}</span><span>¥{{money(o.tablewareAmount)}}</span></div><div class="bill-total"><span>本单金额</span><strong>¥{{money(o.actualAmount)}}</strong></div><p v-if="Number(o.discountRate)<1" class="plain-hint">已按前台确认的折扣计算</p></article>
    <p v-if="!orders.length" class="empty-state">{{ended?'本次账单已结清':'还没有已提交的菜品，去菜单选几道吧。'}}</p><button class="primary wide" @click="view='menu'">{{orders.length?'继续加菜':'看看菜单'}}</button><OrderingHelp/>
   </section>
   <div class="cart-bar" v-if="active&&view==='menu'"><button class="cart-total" @click="modal='cart'"><span class="basket-symbol"><img class="ui-icon" src="/order/icons/basket.svg" alt="" aria-hidden="true"><i v-if="cart.totalCount">{{cart.totalCount}}</i></span><span><strong>¥{{money(cart.totalPrice)}}</strong><small>已选 {{cart.totalCount||0}} 份 · {{needsGuestCount?"菜品金额，餐具另计":"本次加菜，不重复计餐具"}}</small></span></button><button class="primary" @click="modal='cart'" :disabled="!cart.totalCount||busy">选好了</button></div>
   <nav class="bottom-tabs" aria-label="点餐导航"><button :class="{current:view==='menu'}" @click="view='menu'"><img class="ui-icon" src="/order/icons/menu.svg" alt="" aria-hidden="true">菜单</button><button :class="{current:view==='orders'}" @click="view='orders';refresh()"><img class="ui-icon" src="/order/icons/receipt.svg" alt="" aria-hidden="true">本桌订单</button></nav>
  </template>
  <div v-if="modal" class="modal-shade" @click.self="modal=null"><section class="sheet" tabindex="-1" role="dialog" aria-modal="true" :aria-label="modal==='cart'?'购物车':modal==='scan'?'请先关联桌台':'菜品与操作'">
   <header><h2>{{modal==='cart'?'确认选好的菜':modal==='detail'?detail.name:modal==='rejoin'?'开启新一次用餐':modal==='scan'?'请先扫描桌面点餐码':'桌台链接'}}</h2><button class="close" @click="modal=null" aria-label="关闭">×</button></header>
   <template v-if="modal==='detail'"><img class="detail-photo" :src="imageUrl(detail.image||detail.thumbnail)" :alt="detail.name"><p>{{detail.description||'现点现做的家常好味。'}}</p><strong class="detail-price">¥{{money(detail.price)}} / 份</strong><label>口味备注<input v-model="detailRemark" maxlength="150" placeholder="例如：少辣、不要香菜"></label><div class="detail-action"><div class="stepper"><button @click="detailQty=Math.max(1,detailQty-1)">−</button><span>{{detailQty}}</span><button @click="detailQty=Math.min(99,detailQty+1)">+</button></div><button class="primary" @click="addDetail" :disabled="busy||loading">加入购物车</button></div></template>
   <template v-if="modal==='cart'"><p class="plain-hint">{{table?.code}} 桌 · 确认下单后才开台，用餐后统一结账。</p><section v-if="needsGuestCount" class="guest-confirm"><label for="guest-count">本桌用餐人数</label><div class="guest-quick"><button v-for="n in [1,2,3,4,5,6,7,8]" :key="n" :class="{chosen:guestCount===n}" @click="setGuestCount(n)">{{n}}人</button></div><input id="guest-count" type="number" inputmode="numeric" min="1" max="99" step="1" :value="guestCount" @input="setGuestCount($event.target.value)" placeholder="也可直接填写人数"><p>每人一套餐具，1元/套。{{guestCount?'本桌'+guestCount+'人，餐具费 ¥'+money(guestCount):'确认人数后自动加入本桌账单。'}}</p></section><p v-else class="plain-hint">给本桌加菜，餐具费不重复收取；调整人数或套数请联系前台。</p><div class="cart-scroll"><article v-for="i in cart.items" :key="i.dishId" class="cart-item"><div class="cart-line"><b>{{i.dishName}}</b><strong>¥{{money(i.amount)}}</strong><div class="stepper"><button @click="change(i,-1)" :disabled="busy">−</button><span>{{i.quantity}}</span><button @click="change(i,1)" :disabled="busy||i.available===false">+</button></div></div><p v-if="i.available===false" class="unavailable">{{i.unavailableReason}}，请减少或移除后再提交。</p><input v-model="i.remark" @change="updateRemark(i,$event)" maxlength="150" :aria-label="i.dishName+'口味备注'" placeholder="口味备注（选填）"></article><p v-if="!cart.items.length" class="empty-state">购物车还是空的</p></div><label>整单备注<input v-model="orderRemark" maxlength="500" placeholder="有其他要求可以告诉我们"></label><div class="cart-foot"><button @click="clearCart" :disabled="busy||!cart.items.length">清空</button><strong>合计 ¥{{money(cartPayable)}}</strong><button class="primary" @click="submit" :disabled="busy||!cart.items.length||cart.hasUnavailableItems||(needsGuestCount&&(!Number.isInteger(guestCount)||guestCount<1||guestCount>99))">{{busy?'正在提交…':'确认下单'}}</button></div><p class="plain-hint">已经提交的菜需要退换，请联系前台。</p></template>
   <template v-if="modal==='scan'"><p>{{ended?'本次用餐已结束，请重新扫描当前桌面的点餐二维码。':'当前还没有关联桌台，暂时不能把菜加入购物车。'}}</p><p>请用微信“扫一扫”扫描桌面上的点餐二维码，或打开商家发给你的完整桌台点餐链接。</p><button v-if="ended&&scanCode&&scanKey" class="primary wide" @click="modal='rejoin'">已再次入座，重新关联这桌</button><button class="primary wide" @click="modal=null">知道了，继续看菜单</button></template>
   <template v-if="modal==='rejoin'"><p>确认你已再次入座 {{scanCode}} 桌。会关联当前桌次，旧购物车不会带入。</p><button class="primary wide" @click="join" :disabled="busy">确认入座，开始点餐</button></template>
   <template v-if="modal==='link'"><textarea readonly :value="detail.url" @focus="$event.target.select()"></textarea><p>长按复制链接，发给朋友测试。</p></template>
  </section></div>
 </div>`
}).mount('#app');
