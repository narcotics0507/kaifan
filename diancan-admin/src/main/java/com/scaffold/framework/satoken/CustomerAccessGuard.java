package com.scaffold.framework.satoken;
import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.framework.redis.RedisUtils;
import com.scaffold.modules.order.entity.Order;
import com.scaffold.modules.order.mapper.OrderMapper;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.mapper.DiningTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Customer requests must refer to their current table visit. Merchant services stay separate. */
@Component
@RequiredArgsConstructor
public class CustomerAccessGuard {
    private final RedisUtils redis;
    private final OrderMapper orders;
    private final DiningTableMapper tables;
    private boolean customer() {
        return StpUtil.getSession(false)!=null && "APP".equals(StpUtil.getSession(false).get("userType"));
    }
    public void requireTable(Long tableId) {
        if (!customer()) return;
        String openid=SessionUtils.getCurrentOpenid();
        DiningTable table=tables.selectById(tableId);
        Object binding=redis.get("table:user-binding:"+openid);
        if (table==null || table.getCurrentSessionCode()==null || openid.isBlank()
                || !Objects.equals(binding,tableId+"#"+table.getCurrentSessionCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN,"请重新扫码关联当前桌台");
        }
    }
    public void requireOrder(Long orderId, boolean readOnly) {
        if (!customer()) return;
        Order order=orders.selectById(orderId);
        if (order==null) throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        if (readOnly && Objects.equals(order.getCustomerOpenid(),SessionUtils.getCurrentOpenid())) return;
        requireTable(order.getTableId());
        DiningTable table=tables.selectById(order.getTableId());
        if (!Objects.equals(table.getCurrentSessionCode(),order.getTableSessionCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN,"该订单不属于当前桌次");
        }
    }
}
