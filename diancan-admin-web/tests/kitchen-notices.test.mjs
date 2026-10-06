import test from 'node:test';
import assert from 'node:assert/strict';
import {KitchenNoticeTracker,kitchenNoticeText,spokenTableCode,compareKitchenNumbers,initialPapers} from '../src/views/service/kitchen/notices.ts';
const paper=(id,type='TICKET_ORDER',items=[{dishName:'蛋炒饭',quantity:2}])=>({id,type,text:'点菜单\n桌号：A07\n订单：test\n不应从文本推断的菜 × 99',items});
test('Failed first paper request does not swallow additions received before retry',()=>{const t=new KitchenNoticeTracker();const old=paper('old','TICKET_ORDER',[{orderItemId:'11',dishName:'旧菜',quantity:1}]);const added=paper('new','TICKET_ADD',[{orderItemId:'12',dishName:'新加菜',quantity:1}]);const rows=[old,added];t.consume('1','A03',initialPapers(rows,new Set(['11'])),true);const n=t.consume('1','A03',rows);assert.equal(n.length,1);assert.equal(n[0].kind,'add');assert.equal(n[0].items[0].name,'新加菜');assert.deepEqual(t.consume('1','A03',rows),[]);});
test('Opening page primes old papers without announcing',()=>{const t=new KitchenNoticeTracker();assert.deepEqual(t.consume('old','A07',[paper('1')],true),[]);assert(t.isPrimed('old'));assert.deepEqual(t.consume('old','A07',[paper('1')]),[])});
test('New order is announced once across WS plus polling',()=>{const t=new KitchenNoticeTracker();const n=t.consume('new','A07',[paper('2')]);assert.equal(n[0].kind,'new');assert.equal(kitchenNoticeText(n[0]),'A07桌有新订单：蛋炒饭2份。请查看厨房单据。');assert.deepEqual(t.consume('new','A07',[paper('2')]),[])});
test('Whole addition transaction yields one notice with only new quantities',()=>{const t=new KitchenNoticeTracker();t.consume('1','A07',[paper('1')],true);const n=t.consume('1','A07',[paper('1'),paper('2','TICKET_ADD',[{dishName:'蛋炒饭',quantity:1},{dishName:'冬瓜汤',quantity:2}])]);assert.equal(n.length,1);assert.equal(kitchenNoticeText(n[0]),'A07桌加菜：蛋炒饭1份、冬瓜汤2份。请查看厨房单据。')});
test('Consecutive additions are both retained rather than cooldown-dropped',()=>{const t=new KitchenNoticeTracker();const n=t.consume('1','A07',[paper('2','TICKET_ADD'),paper('3','TICKET_ADD')]);assert.equal(n.length,2)});
test('Return/replace and reprint tickets never become new-order notices',()=>{const t=new KitchenNoticeTracker();assert.deepEqual(t.consume('1','A07',[paper('1','TICKET_CHANGE'),paper('2','TICKET_REPRINT')]),[])});
test('Remarks or receipt text cannot add invented dishes to speech',()=>{const t=new KitchenNoticeTracker();const n=t.consume('1','A07',[paper('1')])[0];assert(!kitchenNoticeText(n).includes('99'));assert(!kitchenNoticeText(n).includes('不应'))});
test('Legacy unstructured paper announces a generic reminder',()=>{const t=new KitchenNoticeTracker();const n=t.consume('1','A07',[{id:'1',type:'TICKET_ADD',text:'伪造菜 × 99'}])[0];assert.equal(kitchenNoticeText(n),'A07桌加菜。请查看厨房单据。')});
test('Long identifiers stay distinct',()=>{const t=new KitchenNoticeTracker();assert.equal(t.consume('1','A07',[paper('2106310043893792768'),paper('2106310043893792769')]).length,2)});
test('Long menus are summarized without losing the total count',()=>{const t=new KitchenNoticeTracker();const n=t.consume('1','A07',[paper('1','TICKET_ORDER',Array.from({length:8},(_,i)=>({dishName:'菜'+i,quantity:1})))])[0];assert(kitchenNoticeText(n).includes('等8道菜'));assert(!kitchenNoticeText(n).includes('菜6'))});

test('Numbered new/add announcements use the saved kitchen number',()=>{const t=new KitchenNoticeTracker(),p=paper('n','TICKET_ADD');p.queueDate='2026-10-04';p.queueNumber=3;assert.equal(kitchenNoticeText(t.consume('1','A07',[p])[0]),'厨房顺序003号，A07桌加菜：蛋炒饭2份。请查看厨房单据。')});
test('Daily numbers sort by date before number across midnight',()=>{const rows=[{queueDate:'2026-10-04',queueNumber:3},{queueDate:'2026-10-03',queueNumber:10},{queueDate:'2026-10-04',queueNumber:2}];assert.deepEqual(rows.sort(compareKitchenNumbers).map(r=>r.queueNumber),[10,2,3])});
test('Yesterday large numbers stay ahead of todays reset numbers',()=>{const rows=[{queueDate:'2026-10-05',queueNumber:1},{queueDate:'2026-10-04',queueNumber:999},{queueDate:'2026-10-05',queueNumber:2}];assert.deepEqual(rows.sort(compareKitchenNumbers).map(r=>[r.queueDate,r.queueNumber]),[['2026-10-04',999],['2026-10-05',1],['2026-10-05',2]])});
test('Speech spells letters and preserves leading zeros in table codes',()=>{
  assert.equal(spokenTableCode('A03'),'诶零三');
  assert.equal(spokenTableCode(' b01 '),'比零一');
  assert.equal(spokenTableCode('AB12'),'诶比一二');
  assert.equal(spokenTableCode('003'),'零零三');
  assert.equal(spokenTableCode('大厅03'),'大厅零三');
  assert.equal(spokenTableCode(''),'未知桌号');
});
test('Only speech changes table pronunciation; display and dish names stay intact',()=>{
  const notice={paperId:'speech',kind:'add',tableCode:'A03',queueNumber:4,items:[{name:'A套餐',quantity:2}]};
  assert.equal(kitchenNoticeText(notice),'厨房顺序004号，A03桌加菜：A套餐2份。请查看厨房单据。');
  assert.equal(kitchenNoticeText(notice,true),'诶零三号桌，顺序号零零四，加菜：A套餐2份。请查看厨房单据。');
});
test('Sequence identifiers are spoken digit by digit without losing zeros',()=>{
  const notice={paperId:'speech',kind:'new',tableCode:'A03',items:[]};
  assert.equal(kitchenNoticeText({...notice,queueNumber:1},true),'诶零三号桌，顺序号零零一，有新订单。请查看厨房单据。');
  assert(kitchenNoticeText({...notice,queueNumber:1001},true).includes('顺序号一零零一'));
  assert(!kitchenNoticeText(notice,true).includes('顺序号'));
});
