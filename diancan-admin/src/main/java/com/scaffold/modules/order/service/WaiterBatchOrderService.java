package com.scaffold.modules.order.service;

import cn.hutool.json.JSONUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.modules.order.dto.AddOrderBatchDTO;
import com.scaffold.modules.order.entity.Order;
import com.scaffold.modules.order.entity.OrderOperationLog;
import com.scaffold.modules.order.mapper.OrderMapper;
import com.scaffold.modules.order.mapper.OrderOperationLogMapper;
import com.scaffold.modules.order.vo.OrderVO;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.mapper.DiningTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WaiterBatchOrderService {
    private final OrderMapper orders;
    private final DiningTableMapper tables;
    private final OrderOperationLogMapper logs;
    private final OrderService orderService;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    @Transactional(rollbackFor = Exception.class)
    public OrderVO add(Long orderId, AddOrderBatchDTO request) {
        Order initial = orders.selectById(orderId);
        if (initial == null) throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        // Match the table -> order lock order used by customer submission.
        DiningTable table = tables.selectOne(new LambdaQueryWrapper<DiningTable>()
                .eq(DiningTable::getId, initial.getTableId()).last("FOR UPDATE"));
        Order order = orders.selectByIdForUpdate(orderId);
        if (order == null) throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        String payload = JSONUtil.toJsonStr(request);
        OrderOperationLog previous = logs.selectOne(new LambdaQueryWrapper<OrderOperationLog>()
                .eq(OrderOperationLog::getOrderId, orderId)
                .eq(OrderOperationLog::getOperationType, "WAITER_ADD_BATCH")
                .eq(OrderOperationLog::getReason, request.getRequestId()).last("LIMIT 1 FOR UPDATE"));
        if (previous != null) {
            var accepted = JSONUtil.parseObj(previous.getDetail());
            if (!Objects.equals(accepted.getStr("request"), payload)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "加菜请求已使用，请刷新后核对账单");
            }
            // Replay the committed response; a concurrent repeat may have an older MySQL read snapshot.
            return JSONUtil.toBean(accepted.getJSONObject("result"), OrderVO.class);
        }
        if (table == null || !Integer.valueOf(1).equals(table.getStatus())
                || !Objects.equals(table.getCurrentSessionCode(), request.getTableSessionCode())
                || !Objects.equals(order.getTableSessionCode(), request.getTableSessionCode())
                || !Integer.valueOf(0).equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "本桌账单已变更或已结账，请刷新桌台后重新点餐");
        }
        WaiterBatchStock.begin(redis);
        OrderVO result = null;
        for (var item : request.getItems()) result = orderService.addItem(orderId, item);
        // Nested addItem calls share this transaction and one persisted kitchen ADD ticket.
        OrderOperationLog log = new OrderOperationLog();
        log.setOrderId(orderId);
        log.setOperationType("WAITER_ADD_BATCH");
        log.setOperatorId(StpUtil.getLoginIdAsLong());
        log.setOperatorName("服务员加菜");
        log.setReason(request.getRequestId());
        log.setDetail(JSONUtil.createObj().set("request", payload).set("result", result).toString());
        logs.insert(log);
        return result;
    }
}
