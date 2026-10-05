package com.scaffold.modules.system.service;
import com.scaffold.common.config.WechatProperties;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.redis.RedisUtils;
import com.scaffold.modules.system.service.impl.WechatApiServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WechatConfigurationTest {
    @Test void disabledOrIncompleteConfigurationFailsBeforeNetwork() {
        WechatProperties p=new WechatProperties();
        WechatApiServiceImpl api=new WechatApiServiceImpl(p,mock(RedisUtils.class));
        assertThrows(BusinessException.class,()->api.code2Session("valid-code"));
        p.setEnabled(true);p.setAppId("test-appid");
        assertThrows(BusinessException.class,()->api.code2Session("valid-code"));
    }
}
