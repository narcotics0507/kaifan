package com.scaffold.modules.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scaffold.modules.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 订单实体
 *
 * @author Henfon
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("`order`")
public class Order extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单编号（唯一）
     */
    private String orderNo;

    /**
     * 关联桌台ID
     */
    private Long tableId;

    /**
     * 桌台编号（冗余）
     */
    private String tableCode;

    /**
     * 桌次编码（冗余）
     */
    private String tableSessionCode;

    /**
     * 原始总金额
     */
    /** 本桌用餐人数，历史账单为0（未记录）。 */
    private Integer guestCount;
    /** 独立餐具费用，不能作为菜品参与退菜、赠送或折扣。 */
    private Integer tablewareQuantity;
    private BigDecimal tablewareUnitPrice;
    private BigDecimal tablewareAmount;
    /** 同一桌次只有一个餐具费用归属账单。 */
    private Integer tablewareOwner;

    private BigDecimal originalAmount;

    /**
     * 折扣比例（默认1.00）
     */
    private BigDecimal discountRate;

    /**
     * 优惠券ID
     */
    private Long couponId;

    /**
     * 优惠券名称
     */
    private String couponName;

    /**
     * 优惠券类型（1满减 2折扣）
     */
    private Integer couponType;

    /**
     * 优惠券门槛金额
     */
    private BigDecimal couponThresholdAmount;

    /**
     * 优惠券减免金额
     */
    private BigDecimal couponDiscountAmount;

    /**
     * 优惠券折扣比例
     */
    private BigDecimal couponDiscountRate;

    /**
     * 实付总金额
     */
    private BigDecimal actualAmount;

    /**
     * 使用积分
     */
    private Integer pointsUsed;

    /**
     * 积分抵现金额
     */
    private BigDecimal pointsDiscountAmount;

    /**
     * 已支付金额（AA场景，默认0.00）
     */
    private BigDecimal paidAmount;

    /**
     * 状态（0待支付 1已支付 2已取消 3已退款）
     */
    private Integer status;

    /**
     * 支付模式（0餐前付 1餐后付）
     */
    private Integer paymentMode;

    /**
     * 订单类型（0堂食 1外卖）
     */
    private Integer orderType;

    /**
     * 订单备注
     */
    private String remark;

    /**
     * 顾客微信openid
     */
    private String customerOpenid;
}
