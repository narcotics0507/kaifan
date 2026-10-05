package com.scaffold.modules.dish.service;

import com.scaffold.common.enums.WsEventType;
import com.scaffold.framework.redis.RedisUtils;
import com.scaffold.framework.websocket.WsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/** 菜单变更提交成功后失效缓存并通知终端，避免读到未提交或回滚的数据。 */
@Component
@RequiredArgsConstructor
public class DishMenuChanges {
    public static final String CACHE_KEY = "dish:orderable-list:v1";
    private final RedisUtils redisUtils;
    private final WsService wsService;

    public void changed() {
        Runnable action = () -> {
            redisUtils.delete(CACHE_KEY);
            redisUtils.delete("dish:list");
            wsService.broadcast(WsEventType.MENU_CHANGED, "/topic/sold-out", Map.of());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
