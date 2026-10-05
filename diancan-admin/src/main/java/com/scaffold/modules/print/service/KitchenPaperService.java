package com.scaffold.modules.print.service;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.modules.order.entity.*;
import com.scaffold.modules.order.mapper.OrderOperationLogMapper;
import com.scaffold.modules.order.service.OrderReturnRecords;
import com.scaffold.modules.print.vo.KitchenPaperVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.util.*;
@Component @RequiredArgsConstructor
public class KitchenPaperService {
 private static final Object BATCH_KEY=new Object();
 private final OrderOperationLogMapper mapper;
 private final com.scaffold.modules.order.mapper.OrderMapper orders;
 private final com.scaffold.modules.order.mapper.OrderItemMapper items;
 private final OrderReturnRecords returns;
 private final KitchenSequenceService sequenceService;
 @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
 @SuppressWarnings("unchecked")
 public void recordItems(Order order,List<OrderItem> items,String type) {
  if(items.isEmpty())return;
  // A zero-value gifted bill can close while the table visit continues. A later bill is still an addition for this visit.
  if("ORDER".equals(type) && order.getTableId()!=null && order.getTableSessionCode()!=null && orders.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getTableId,order.getTableId()).eq(Order::getTableSessionCode,order.getTableSessionCode()).ne(Order::getId,order.getId()))>0)type="ADD";
  Map<String,OrderOperationLog> batch;
  if(TransactionSynchronizationManager.isSynchronizationActive()) {
   if(!TransactionSynchronizationManager.hasResource(BATCH_KEY)) {
    TransactionSynchronizationManager.bindResource(BATCH_KEY,new LinkedHashMap<String,OrderOperationLog>());
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
     @Override public void beforeCommit(boolean readOnly){
      Map<String,OrderOperationLog> pending=(Map<String,OrderOperationLog>)TransactionSynchronizationManager.getResource(BATCH_KEY);
      for(OrderOperationLog record:pending.values()){
       var data=JSONUtil.parseObj(record.getDetail());var sequence=sequenceService.next();data.set("queueDate",sequence.date().toString());data.set("queueNumber",sequence.number());data.set("text","厨房顺序："+sequence.date()+" "+String.format("%03d",sequence.number())+"号\n"+data.getStr("text"));record.setDetail(JSONUtil.toJsonStr(data));mapper.updateById(record);
      }
     }
     @Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(BATCH_KEY);}
    });
   }
   batch=(Map<String,OrderOperationLog>)TransactionSynchronizationManager.getResource(BATCH_KEY);
  }else batch=new HashMap<>();
  String key=order.getId()+":"+type;
  StringBuilder text=new StringBuilder();
  text.append(type.equals("ORDER")?"点菜单":"加菜单").append("\n桌号：").append(order.getTableCode()).append("\n订单：").append(order.getOrderNo()).append("\n");
  for(OrderItem item:items)text.append(item.getDishName()).append(" × ").append(item.getQuantity()).append(item.getRemark()==null?"":"（"+item.getRemark()+"）").append("\n");
  OrderOperationLog existing=batch.get(key);
  if(existing==null){existing=save(order,"TICKET_"+type,text.toString(),snapshots(items));batch.put(key,existing);}
  else {var old=JSONUtil.parseObj(existing.getDetail());var allItems=old.getJSONArray("items")==null?new ArrayList<com.scaffold.modules.print.vo.KitchenPaperItemVO>():new ArrayList<>(JSONUtil.toList(old.getJSONArray("items"),com.scaffold.modules.print.vo.KitchenPaperItemVO.class));allItems.addAll(snapshots(items));old.set("text",old.getStr("text")+text.substring(text.indexOf("\n",text.indexOf("订单："))+1));old.set("items",allItems);existing.setDetail(JSONUtil.toJsonStr(old));mapper.updateById(existing);}
 }
 public void recordChange(Order order,OrderItem oldItem,String type,String reason,List<OrderItem> replacements) {
  StringBuilder text=new StringBuilder("厨房变更通知\n桌号：").append(order.getTableCode()).append("\n订单：").append(order.getOrderNo()).append("\n取消，不再制作：").append(oldItem.getDishName()).append(" × ").append(oldItem.getQuantity()).append("\n原因：").append(reason);
  for(OrderItem item:replacements)text.append("\n改做：").append(item.getDishName()).append(" × ").append(item.getQuantity()).append(item.getRemark()==null?"":"（"+item.getRemark()+"）");
  save(order,"TICKET_CHANGE",text.toString());
 }
 public void recordReprint(Order order,List<OrderItem> items) {save(order,"TICKET_REPRINT",receipt(order,items));}
 private List<com.scaffold.modules.print.vo.KitchenPaperItemVO> snapshots(List<OrderItem> rows) {
  return rows.stream().map(item->{var snapshot=new com.scaffold.modules.print.vo.KitchenPaperItemVO();snapshot.setOrderItemId(item.getId());snapshot.setRemark(item.getRemark());snapshot.setDishName(item.getDishName());snapshot.setQuantity(item.getQuantity());return snapshot;}).toList();
 }
 private OrderOperationLog save(Order order,String type,String text) {return save(order,type,text,List.of());}
 private OrderOperationLog save(Order order,String type,String text,List<com.scaffold.modules.print.vo.KitchenPaperItemVO> snapshots) {
  OrderOperationLog log=new OrderOperationLog();log.setOrderId(order.getId());log.setOperationType(type);try{log.setOperatorId(cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong());}catch(Exception ignored){log.setOperatorId(0L);}log.setOperatorName("单据记录");log.setReason("实体打印机尚未接入");Map<String,Object> data=new LinkedHashMap<>();data.put("text",text);data.put("status","WAITING_DEVICE");data.put("items",snapshots);
  log.setDetail(JSONUtil.toJsonStr(data));mapper.insert(log);return log;
 }
 public List<KitchenPaperVO> list(Long orderId) {
  return mapper.selectList(new LambdaQueryWrapper<OrderOperationLog>().eq(OrderOperationLog::getOrderId,orderId).in(OrderOperationLog::getOperationType,"TICKET_ORDER","TICKET_ADD","TICKET_CHANGE","TICKET_REPRINT").orderByAsc(OrderOperationLog::getCreateTime).orderByAsc(OrderOperationLog::getId)).stream().map(log->{var data=JSONUtil.parseObj(log.getDetail());KitchenPaperVO vo=new KitchenPaperVO();vo.setId(log.getId());vo.setOrderId(log.getOrderId());Order order=orders.selectById(log.getOrderId());vo.setTableCode(order==null?null:order.getTableCode());if(data.getStr("queueDate")!=null)vo.setQueueDate(java.time.LocalDate.parse(data.getStr("queueDate")));vo.setQueueNumber(data.getInt("queueNumber"));vo.setType(log.getOperationType());vo.setText(data.getStr("text"));if(data.getJSONArray("items")!=null)vo.setItems(JSONUtil.toList(data.getJSONArray("items"),com.scaffold.modules.print.vo.KitchenPaperItemVO.class));vo.setCreateTime(log.getCreateTime());return vo;}).toList();
 }
 @org.springframework.transaction.annotation.Transactional
 public String receiptSnapshot(Long orderId) {
  Order order=orders.selectByIdForUpdate(orderId);
  if(order==null)throw new com.scaffold.common.exception.BusinessException(com.scaffold.common.result.ResultCode.ORDER_NOT_FOUND);
  return receipt(order,items.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId,orderId)));
 }
 public String receipt(Order order,List<OrderItem> items) {
  StringBuilder text=new StringBuilder("结账单（按最新账单）\n桌号：").append(order.getTableCode()).append("\n订单：").append(order.getOrderNo()).append("\n");
  for(OrderItem item:items)text.append(item.getDishName()).append(" × ").append(item.getQuantity()).append(Integer.valueOf(1).equals(item.getIsGift())?"  免单，不收费 ￥":"  ￥").append(item.getAmount()).append("\n");
  returns.list(order.getId()).forEach(item->text.append(item.getDishName()).append(" × ").append(item.getQuantity()).append("  已退，不收费 ￥0.00（").append(item.getReason()).append("）\n"));
  if(order.getDiscountRate()!=null && order.getDiscountRate().compareTo(java.math.BigDecimal.ONE)<0)text.append("折扣：").append(order.getDiscountRate().multiply(java.math.BigDecimal.TEN).stripTrailingZeros().toPlainString()).append("折\n");
  return text.append("应收合计：￥").append(order.getActualAmount()).toString();
 }
}
