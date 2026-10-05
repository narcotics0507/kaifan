package com.scaffold.modules.print.vo;
import lombok.Data;
/** Immutable dish quantities on this paper; independent of subsequent bill adjustments. */
@Data public class KitchenPaperItemVO {
 private Long orderItemId;
 private String remark;
 private String dishName;
 private Integer quantity;
}
