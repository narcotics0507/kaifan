package com.scaffold.framework.websocket;

import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.modules.system.entity.SysUser;
import com.scaffold.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import java.security.Principal;

/** SockJS handshake may be public; business topics require a merchant token. */
@Component
@RequiredArgsConstructor
public class MerchantStompInterceptor implements ChannelInterceptor {
    private final SysUserMapper users;
    private record MerchantPrincipal(String token, String userId) implements Principal {
        @Override public String getName() { return userId; }
    }
    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor h=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if (h==null || h.getCommand()==null) return message;
        if (h.getCommand()==StompCommand.CONNECT) {
            String token=h.getFirstNativeHeader("Authorization");
            if (token!=null && token.startsWith("Bearer ")) token=token.substring(7);
            String id=checkMerchant(token);
            h.setUser(new MerchantPrincipal(token,id));
        } else if (h.getCommand()==StompCommand.SUBSCRIBE || h.getCommand()==StompCommand.SEND) {
            if (!(h.getUser() instanceof MerchantPrincipal principal)) {
                throw new MessageDeliveryException("商家实时连接需要登录");
            }
            checkMerchant(principal.token());
            if (h.getCommand()==StompCommand.SEND) {
                throw new MessageDeliveryException("商家实时连接仅接收通知");
            }
        }
        return message;
    }
    private String checkMerchant(String token) {
        Object loginId=token==null || token.isBlank()?null:StpUtil.getLoginIdByToken(token);
        if (loginId==null) throw new MessageDeliveryException("商家实时连接需要登录");
        SysUser user=users.selectById(Long.valueOf(loginId.toString()));
        if (user==null || !"BACKEND".equals(user.getUserType()) || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new MessageDeliveryException("商家实时连接无权限");
        }
        return loginId.toString();
    }
}
