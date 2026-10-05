package com.scaffold.modules.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class AddOrderBatchDTO {
    @NotBlank @Size(max = 80)
    private String requestId;
    @NotBlank @Size(max = 100)
    private String tableSessionCode;
    @NotEmpty @Size(max = 100) @Valid
    private List<AddItemDTO> items;
}
