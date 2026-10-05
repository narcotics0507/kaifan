package com.scaffold.modules.order.vo;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class ReturnedItemVO {
 private Long id;
 private Long orderItemId;
 private String dishName;
 private Integer quantity;
 private String reason;
 private String kind;
 private BigDecimal amount = BigDecimal.ZERO;
 private LocalDateTime createTime;
}
