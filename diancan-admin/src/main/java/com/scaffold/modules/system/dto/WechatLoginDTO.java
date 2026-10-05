package com.scaffold.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 小程序 wx.login 临时凭证；不接收客户端传入的 OpenID。 */
@Data
public class WechatLoginDTO {
    @NotBlank(message = "code不能为空")
    @Size(max = 256, message = "code长度无效")
    private String code;
}
