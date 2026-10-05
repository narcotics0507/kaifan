package com.scaffold.modules.print.vo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class KitchenPaperVO {
 private Long id;
 private Long orderId;
 private String tableCode;
 private java.time.LocalDate queueDate;
 private Integer queueNumber;
 private String type;
 private String status="WAITING_DEVICE";
 private String text;
 private java.util.List<KitchenPaperItemVO> items=java.util.List.of();
 private LocalDateTime createTime;
}
