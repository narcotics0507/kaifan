package com.scaffold.modules.order.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class ShortageReturnDTO {
 @NotNull @Min(1) private Integer quantity;
 private Boolean notifyKitchen = false;
 @NotBlank @Size(max=64) @Pattern(regexp="[a-zA-Z0-9-]+") private String requestId;
}
