package com.scaffold.modules.order.service;

import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.modules.order.entity.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** Fixed-price, separately accounted tableware for one dining visit. */
@Component
public class TablewareBilling {
    public static final BigDecimal UNIT_PRICE = new BigDecimal("1.00");
    // Legacy monetary fixtures may omit people; production always requires confirmation.
    @Value("${restaurant.tableware.require-guest-count:true}")
    private boolean requireGuestCount;

    public void initialize(Order order, Integer guests, boolean firstBill) {
        order.setTablewareUnitPrice(UNIT_PRICE);
        order.setGuestCount(guests == null ? 0 : guests);
        order.setTablewareOwner(firstBill ? 1 : 0);
        if (firstBill && guests == null && requireGuestCount) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请刷新点餐页，先确认本桌用餐人数（每人一套餐具，1元/套）");
        }
        if (guests != null && (guests < 1 || guests > 99)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用餐人数须为1至99人的整数");
        }
        order.setTablewareQuantity(firstBill && guests != null ? guests : 0);
        order.setTablewareAmount(UNIT_PRICE.multiply(BigDecimal.valueOf(order.getTablewareQuantity())));
    }

    public static BigDecimal amount(Order order) {
        return order.getTablewareAmount() == null ? BigDecimal.ZERO : order.getTablewareAmount();
    }
}
