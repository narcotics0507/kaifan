package com.scaffold.modules.report.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Cash ledger by Shanghai calendar date. No customer identity or payment secrets. */
@Data
public class RevenueDailyVO {
    private String date;
    private BigDecimal totalRevenue = BigDecimal.ZERO;
    private BigDecimal receivedAmount = BigDecimal.ZERO;
    private BigDecimal refundAmount = BigDecimal.ZERO;
    private BigDecimal wechatAmount = BigDecimal.ZERO;
    private BigDecimal alipayAmount = BigDecimal.ZERO;
    private BigDecimal cashAmount = BigDecimal.ZERO;
    private BigDecimal otherAmount = BigDecimal.ZERO;
    private Integer orderCount = 0;
    private Integer openedOrderCount = 0;
    private BigDecimal unsettledAmount = BigDecimal.ZERO;
    private BigDecimal returnedAmount = BigDecimal.ZERO;
    private BigDecimal waivedAmount = BigDecimal.ZERO;
    private List<Bill> orders = new ArrayList<>();
    private List<Receipt> payments = new ArrayList<>();
    private List<Adjustment> adjustments = new ArrayList<>();

    @Data
    public static class Bill {
        private String id;
        private String orderNo;
        private String tableCode;
        private String tableSessionCode;
        private LocalDateTime createTime;
        private String status;
        private BigDecimal originalAmount;
        private BigDecimal discountAmount;
        private BigDecimal actualAmount;
        private BigDecimal paidAmount;
        private BigDecimal unsettledAmount;
        private BigDecimal dayReceivedAmount;
        private BigDecimal dayRefundAmount;
        private BigDecimal dayNetAmount;
        private String paymentMethods;
        private String remark;
        private List<Item> items = new ArrayList<>();
        private List<Adjustment> adjustments = new ArrayList<>();
    }

    @Data
    public static class Item {
        private String id;
        private String dishName;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal amount;
        private String billingStatus;
        private String remark;
        private LocalDateTime addedAt;
    }

    @Data
    public static class Receipt {
        private String id;
        private String orderId;
        private String orderNo;
        private String tableCode;
        private String paymentNo;
        private LocalDateTime time;
        private String kind;
        private String paymentMethod;
        private BigDecimal amount;
        private String operatorName;
        private String reason;
    }

    @Data
    public static class Adjustment {
        private String id;
        private String orderId;
        private String orderNo;
        private String tableCode;
        private LocalDateTime time;
        private String kind;
        private String dishName;
        private Integer quantity;
        // Null means the historical log did not record an amount, never a guessed zero.
        private BigDecimal amount;
        private String operatorName;
        private String reason;
        private String description;
    }
}
