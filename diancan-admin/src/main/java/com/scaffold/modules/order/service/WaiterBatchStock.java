package com.scaffold.modules.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Track only deductions made inside the new waiter batch, including failures after Redis succeeds. */
@Slf4j
public final class WaiterBatchStock {
    private static final Object KEY = new Object();
    private WaiterBatchStock() {}

    public static void begin(StringRedisTemplate redis) {
        if (TransactionSynchronizationManager.hasResource(KEY)) return;
        Map<Long, Long> deductions = new LinkedHashMap<>();
        TransactionSynchronizationManager.bindResource(KEY, deductions);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try {
                    if (status != STATUS_COMMITTED) deductions.forEach((id, quantity) -> {
                        try { redis.opsForValue().increment("dish:stock:" + id, quantity); }
                        catch (Exception failure) { log.error("服务员加菜库存回补失败: dishId={}", id, failure); }
                    });
                } finally { TransactionSynchronizationManager.unbindResourceIfPossible(KEY); }
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static void deducted(Long dishId, int quantity) {
        var deductions = (Map<Long, Long>) TransactionSynchronizationManager.getResource(KEY);
        if (deductions != null) deductions.merge(dishId, (long) quantity, Long::sum);
    }
}
