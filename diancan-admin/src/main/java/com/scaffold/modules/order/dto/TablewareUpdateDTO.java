package com.scaffold.modules.order.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class TablewareUpdateDTO {
    @NotNull @Min(1) @Max(99)
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = TablewareCountDeserializer.class)
    private Integer guestCount;
    @NotNull @Min(0) @Max(99)
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = TablewareCountDeserializer.class)
    private Integer quantity;
    @NotBlank @Size(max = 150)
    private String reason;
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,80}")
    private String requestId;
}
