package com.scaffold.modules.order.service;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.modules.order.entity.OrderOperationLog;
import com.scaffold.modules.order.mapper.OrderOperationLogMapper;
import com.scaffold.modules.order.vo.ReturnedItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class OrderReturnRecords {
 private final OrderOperationLogMapper mapper;
 public List<ReturnedItemVO> list(Long orderId) {
  List<ReturnedItemVO> result = new ArrayList<>();
  for (OrderOperationLog log : mapper.selectList(new LambdaQueryWrapper<OrderOperationLog>().eq(OrderOperationLog::getOrderId,orderId).in(OrderOperationLog::getOperationType,"RETURN","SHORTAGE_RETURN","REPLACE").orderByAsc(OrderOperationLog::getCreateTime))) {
   try {
    var data=JSONUtil.parseObj(log.getDetail());
    ReturnedItemVO item=new ReturnedItemVO();item.setId(log.getId());item.setOrderItemId(log.getOrderItemId());item.setReason(log.getReason());item.setKind(log.getOperationType());item.setCreateTime(log.getCreateTime());
    item.setDishName(data.getStr("dishName",data.getStr("oldDish")));item.setQuantity(data.getInt("quantity",data.getInt("oldQuantity")));
    if(item.getDishName()!=null&&item.getQuantity()!=null)result.add(item);
   }catch(Exception ignored){ /* Legacy malformed logs never change billing. */ }
  }
  return result;
 }
}
