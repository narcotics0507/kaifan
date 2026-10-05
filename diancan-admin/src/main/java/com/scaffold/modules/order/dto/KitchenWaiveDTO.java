package com.scaffold.modules.order.dto;
import lombok.Data;import jakarta.validation.constraints.*;
@Data public class KitchenWaiveDTO {
 @NotBlank @Pattern(regexp="[a-zA-Z0-9-]{1,64}") private String requestId;
 @NotBlank @Size(max=150) private String reason;
}
