package com.scaffold.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 微信小程序配置属性
 *
 * @author Henfon
 * @date 2025/06/25
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatProperties {

    /** 仅在私密服务端配置就绪后开启真实微信能力。 */
    private boolean enabled = false;

    /** 堂食登录不需要手机号；兼容接口默认关闭。 */
    private boolean phoneLoginEnabled = false;

    /** 小程序 appId */
    private String appId;

    /** 小程序 appSecret */
    private String appSecret;
}
