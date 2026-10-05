export interface NoticePaper { id: string | number; type: string; text: string; queueDate?: string; queueNumber?: number; items?: Array<{ dishName: string; quantity: number }>; }
export interface KitchenNotice { paperId: string; kind: 'new' | 'add'; queueDate?: string; queueNumber?: number; tableCode: string; items: Array<{ name: string; quantity: number }>; }
/** The persisted paper identifies a transaction/batch, rather than a repeated WS frame or poll. */
export class KitchenNoticeTracker {
  private seen = new Set<string>();
  private primed = new Set<string>();
  isPrimed(orderId: string | number) { return this.primed.has(String(orderId)); }
  consume(orderId: string | number, tableCode: string, papers: NoticePaper[], baseline = false): KitchenNotice[] {
    const notices: KitchenNotice[] = [];
    for (const paper of papers) {
      const id = String(paper.id);
      if (this.seen.has(id)) continue;
      this.seen.add(id);
      if (baseline || !['TICKET_ORDER', 'TICKET_ADD'].includes(paper.type)) continue;
      // Never infer dish rows from remarks or human-readable receipt text.
      const items = (paper.items || []).filter(item => item.quantity > 0).map(item => ({ name: item.dishName, quantity: item.quantity }));
      notices.push({ paperId: id, queueDate: paper.queueDate, queueNumber: paper.queueNumber, kind: paper.type === 'TICKET_ORDER' ? 'new' : 'add', tableCode, items });
    }
    this.primed.add(String(orderId));
    return notices;
  }
}
// Use Chinese phonetic text for codes so a Chinese voice does not treat A as an English article.
const spokenLetters: Record<string, string> = {
  A: '诶', B: '比', C: '西', D: '迪', E: '伊', F: '艾弗', G: '吉', H: '艾尺',
  I: '爱', J: '杰', K: '开', L: '艾尔', M: '艾姆', N: '恩', O: '欧', P: '皮',
  Q: '丘', R: '阿尔', S: '艾斯', T: '提', U: '优', V: '维', W: '达布流', X: '艾克斯', Y: '歪', Z: '贼德'
};
export function spokenTableCode(code: string) {
  const value = code.trim();
  if (!value) return '未知桌号';
  return value.replace(/[a-z]+|\d+/gi, part => /^\d+$/.test(part)
    ? Array.from(part, digit => '零一二三四五六七八九'[Number(digit)]).join('')
    : Array.from(part.toUpperCase(), letter => spokenLetters[letter]).join(''));
}
const spokenDigits = (value: string) => value.replace(/\d/g, digit => '零一二三四五六七八九'[Number(digit)]);
export function kitchenNoticeText(notice: KitchenNotice, spoken = false) {
  const summary = notice.items.slice(0, 6).map(item => `${item.name}${item.quantity}份`).join('、');
  const more = notice.items.length > 6 ? `等${notice.items.length}道菜` : '';
  const number = notice.queueNumber == null ? '' : String(notice.queueNumber).padStart(3,'0');
  const sequence = !number ? '' : spoken ? `，顺序号${spokenDigits(number)}` : `厨房顺序${number}号，`;
  const table = spoken ? `${spokenTableCode(notice.tableCode)}号桌` : `${notice.tableCode || '未知桌号'}桌`;
  const prefix = spoken ? `${table}${sequence}，` : `${sequence}${table}`;
  return `${prefix}${notice.kind === 'new' ? '有新订单' : '加菜'}${summary ? '：' + summary + more : ''}。请查看厨房单据。`;
}

export function compareKitchenNumbers(a:{queueDate?:string;queueNumber?:number;createTime?:string},b:{queueDate?:string;queueNumber?:number;createTime?:string}){return (a.queueNumber||0)-(b.queueNumber||0) || (a.queueDate||a.createTime?.slice(0,10)||'').localeCompare(b.queueDate||b.createTime?.slice(0,10)||'')}
