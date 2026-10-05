package com.scaffold.framework.websocket;
import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.modules.system.mapper.SysUserMapper;
import com.scaffold.modules.system.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MerchantStompInterceptorTest {
    private Message<byte[]> frame(StompHeaderAccessor h) { h.setLeaveMutable(true);return MessageBuilder.createMessage(new byte[0],h.getMessageHeaders()); }
    @Test void rejectsAnonymousAndCustomerTokens() {
        SysUserMapper users=mock(SysUserMapper.class);MerchantStompInterceptor i=new MerchantStompInterceptor(users);
        assertThrows(MessageDeliveryException.class,()->i.preSend(frame(StompHeaderAccessor.create(StompCommand.CONNECT)),null));
        try(MockedStatic<StpUtil> token=mockStatic(StpUtil.class)) {
            token.when(()->StpUtil.getLoginIdByToken("customer-token")).thenReturn("23");
            SysUser u=new SysUser();u.setUserType("APP");u.setStatus(1);when(users.selectById(23L)).thenReturn(u);
            StompHeaderAccessor h=StompHeaderAccessor.create(StompCommand.CONNECT);h.setNativeHeader("Authorization","customer-token");
            assertThrows(MessageDeliveryException.class,()->i.preSend(frame(h),null));
        }
    }
    @Test void merchantConnectsButExpiredSubscriptionFails() {
        SysUserMapper users=mock(SysUserMapper.class);MerchantStompInterceptor i=new MerchantStompInterceptor(users);
        try(MockedStatic<StpUtil> token=mockStatic(StpUtil.class)) {
            token.when(()->StpUtil.getLoginIdByToken("merchant-token")).thenReturn("24");
            SysUser u=new SysUser();u.setUserType("BACKEND");u.setStatus(1);when(users.selectById(24L)).thenReturn(u);
            StompHeaderAccessor h=StompHeaderAccessor.create(StompCommand.CONNECT);h.setNativeHeader("Authorization","Bearer merchant-token");
            assertNotNull(i.preSend(frame(h),null));assertEquals("24",h.getUser().getName());
            StompHeaderAccessor sub=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);sub.setUser(h.getUser());sub.setDestination("/topic/kitchen");
            assertNotNull(i.preSend(frame(sub),null));
            token.when(()->StpUtil.getLoginIdByToken("merchant-token")).thenReturn(null);
            assertThrows(MessageDeliveryException.class,()->i.preSend(frame(sub),null));
        }
    }
}
