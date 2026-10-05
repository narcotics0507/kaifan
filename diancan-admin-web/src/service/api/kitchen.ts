import { request } from '../request';

/** 后厨任务VO类型 */
export interface KitchenTask {
  id: number;
  orderId: number;
  orderNo: string;
  tableCode: string;
  areaName: string | null;
  paymentMode: number;
  dishId: number;
  dishName: string;
  dishImage: string | null;
  soldOut: 0 | 1;
  quantity: number;
  remark: string | null;
  status: number;
  addedAt: string;
  preparationTime: number | null;
  overtime: boolean;
}

/** 获取后厨任务列表 */
export function fetchKitchenTasks() {
  return request<KitchenTask[]>({ url: '/app/kitchen/tasks', method: 'get' });
}

/** 接单 */
export function acceptKitchenTask(itemId: number) {
  return request<void>({ url: `/app/kitchen/task/${itemId}/accept`, method: 'put' });
}

/** 标记菜品做好 */
export function completeKitchenTask(itemId: number) {
  return request<void>({ url: `/app/kitchen/task/${itemId}/complete`, method: 'put' });
}

/** 标记卖完了/恢复可点（后厨） */
export function markKitchenDishSoldOut(dishId: number, soldOut: 0 | 1) {
  return request<void>({ url: `/app/dish/${dishId}/sold-out`, method: 'put', params: { soldOut } });
}

/** 获取后厨自动接单开关 */
export function fetchKitchenAutoAcceptEnabled() {
  return request<boolean>({ url: '/app/kitchen/auto-accept', method: 'get' });
}

/** 更新后厨自动接单开关 */
export function updateKitchenAutoAcceptEnabled(enabled: boolean) {
  return request<void>({ url: '/app/kitchen/auto-accept', method: 'put', params: { enabled } });
}

export interface KitchenPaperItem { orderItemId?: Api.Business.IdType; dishName: string; quantity: number; remark?: string; }
export interface KitchenPaper { id: Api.Business.IdType; type: string; status: string; text: string; items?: KitchenPaperItem[]; orderId?: Api.Business.IdType; tableCode?: string; queueDate?: string; queueNumber?: number; createTime: string; }
export function fetchKitchenBills(){return request<Api.Business.Order[]>({url:'/app/kitchen/bills',method:'get'});}
export function shortageReturn(itemId: Api.Business.IdType,quantity:number,requestId:string,notifyKitchen=false){return request<Api.Business.Order>({url:`/app/kitchen/item/${itemId}/shortage-return`,method:'post',data:{quantity,requestId,notifyKitchen}});}
export function fetchKitchenPapers(orderId: Api.Business.IdType){return request<KitchenPaper[]>({url:`/app/kitchen/papers/${orderId}`,method:'get'});}
export function fetchKitchenReceipt(orderId: Api.Business.IdType){return request<string>({url:`/app/kitchen/receipt/${orderId}`,method:'get'});}

export function waiveKitchenItem(itemId: Api.Business.IdType,requestId:string,reason:string){return request<Api.Business.Order>({url:`/app/kitchen/item/${itemId}/waive`,method:'post',data:{requestId,reason}});}
