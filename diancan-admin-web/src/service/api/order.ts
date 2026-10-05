import { request } from '../request';

/** 获取订单列表（管理端分页） */
export function fetchOrderList(params: Api.Business.OrderQuery & { pageNum?: number; pageSize?: number }) {
  return request<Api.System.PageResult<Api.Business.Order>>({
    url: '/admin/order/list',
    method: 'get',
    params
  });
}

/** 获取订单详情 */
export function fetchOrderDetail(id: Api.Business.IdType) {
  return request<Api.Business.OrderDetail>({
    url: `/admin/order/${id}`,
    method: 'get'
  });
}

/** 人数与餐具仅由商家在收款前调整，服务端固定单价并保留审计。 */
export function updateOrderTableware(id: Api.Business.IdType, data: { guestCount: number; quantity: number; reason: string; requestId: string }) {
  return request<Api.Business.Order>({ url: `/admin/order/${id}/tableware`, method: 'put', data });
}
