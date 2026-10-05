package com.scaffold.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.DiancanAdminApplication;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.dish.dto.DishCreateDTO;
import com.scaffold.modules.dish.service.DishService;
import com.scaffold.modules.order.dto.*;
import com.scaffold.modules.order.service.*;
import com.scaffold.modules.order.vo.OrderVO;
import com.scaffold.modules.print.service.KitchenPaperService;
import com.scaffold.modules.table.dto.TableCreateDTO;
import com.scaffold.modules.table.service.DiningTableService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = DiancanAdminApplication.class)
@ActiveProfiles("test")
class WaiterBatchOrderIntegrationTest {
    @Autowired WaiterBatchOrderService batch;
    @Autowired OrderService orders;
    @Autowired DiningTableService tables;
    @Autowired DishService dishes;
    @Autowired com.scaffold.modules.dish.service.DishCategoryService categories;
    @Autowired KitchenPaperService papers;
    @Autowired StringRedisTemplate redis;
    @MockBean WsService websocket;
    private MockedStatic<StpUtil> login;
    private Long dishId;
    private OrderVO first;

    @BeforeEach void setup() {
        login = Mockito.mockStatic(StpUtil.class);
        login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
        login.when(StpUtil::getLoginIdAsString).thenReturn("1");
        String name = "waiter-" + UUID.randomUUID().toString().substring(0, 8);
        var category = new com.scaffold.modules.dish.dto.DishCategoryCreateDTO();
        category.setName(name); category.setSort(1); categories.createCategory(category);
        Long categoryId = categories.list().stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow().getId();
        DishCreateDTO dish = new DishCreateDTO();
        dish.setName(name); dish.setCategoryId(categoryId); dish.setPrice(new BigDecimal("10.00"));
        dish.setStock(20); dish.setSpiceLevel(0);
        dishes.createDish(dish);
        dishId = dishes.list().stream().filter(d -> name.equals(d.getName())).findFirst().orElseThrow().getId();
        TableCreateDTO table = new TableCreateDTO();
        table.setCode(name); table.setName(name); table.setCapacity(4); tables.createTable(table);
        Long tableId = tables.list().stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow().getId();
        AdminOrderCreateDTO create = new AdminOrderCreateDTO();
        create.setTableId(tableId); create.setPaymentMode(1);
        AdminOrderCreateDTO.AdminOrderItemDTO item = new AdminOrderCreateDTO.AdminOrderItemDTO();
        item.setDishId(dishId); item.setQuantity(1); create.setItems(List.of(item));
        first = orders.createAdminOrder(create);
    }

    @AfterEach void close() { if (login != null) login.close(); }

    private AddItemDTO item(Long id, int quantity) {
        AddItemDTO item = new AddItemDTO(); item.setDishId(id); item.setQuantity(quantity); return item;
    }
    private AddOrderBatchDTO request(List<AddItemDTO> items) {
        AddOrderBatchDTO request = new AddOrderBatchDTO(); request.setRequestId(UUID.randomUUID().toString());
        request.setTableSessionCode(first.getTableSessionCode()); request.setItems(items); return request;
    }

    @Test void batchAddsToSameBillWithOneNumberAndReplayDoesNotDuplicate() {
        var request = request(List.of(item(dishId, 2), item(dishId, 3)));
        var result = batch.add(first.getId(), request);
        assertEquals(first.getId(), result.getId());
        assertEquals(0, new BigDecimal("60.00").compareTo(result.getActualAmount()));
        var additions = papers.list(first.getId()).stream().filter(p -> p.getType().equals("TICKET_ADD")).toList();
        assertEquals(1, additions.size()); assertEquals(2, additions.get(0).getItems().size());
        assertNotNull(additions.get(0).getQueueNumber());
        assertEquals(0, result.getActualAmount().compareTo(batch.add(first.getId(), request).getActualAmount()));
        assertEquals(2, papers.list(first.getId()).size());
        assertEquals("14", redis.opsForValue().get("dish:stock:" + dishId));
        request.setItems(List.of(item(dishId, 1)));
        assertThrows(BusinessException.class, () -> batch.add(first.getId(), request));
    }

    @Test void laterFailureRollsBackItemsBillTicketAndRedisStock() {
        var request = request(List.of(item(dishId, 2), item(dishId, 99)));
        assertThrows(BusinessException.class, () -> batch.add(first.getId(), request));
        var result = orders.getOrderDetail(first.getId());
        assertEquals(1, result.getItems().size());
        assertEquals(0, new BigDecimal("10.00").compareTo(result.getActualAmount()));
        assertEquals(1, papers.list(first.getId()).size());
        assertEquals(19, dishes.getById(dishId).getStock());
        assertEquals("19", redis.opsForValue().get("dish:stock:" + dishId));
    }

    @Test void rejectsChangedVisitAndSettledBill() {
        var request = request(List.of(item(dishId, 1)));
        request.setTableSessionCode("another-visit");
        assertThrows(BusinessException.class, () -> batch.add(first.getId(), request));
        request.setTableSessionCode(first.getTableSessionCode());
        var order = orders.getById(first.getId()); order.setStatus(1); orders.updateById(order);
        assertThrows(BusinessException.class, () -> batch.add(first.getId(), request));
        assertEquals(1, papers.list(first.getId()).size());
    }

    @Test void concurrentRetryAddsOnlyOneBatch() throws Exception {
        var request = request(List.of(item(dishId, 2)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<OrderVO> call = () -> {
            try (var threadLogin = Mockito.mockStatic(StpUtil.class)) {
                threadLogin.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                threadLogin.when(StpUtil::getLoginIdAsString).thenReturn("1");
                start.await(); return batch.add(first.getId(), request);
            }
        };
        try {
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var firstResult = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var replay = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, firstResult.getActualAmount().compareTo(replay.getActualAmount()));
            assertEquals(0, new BigDecimal("30.00").compareTo(replay.getActualAmount()));
            assertEquals(2, papers.list(first.getId()).size());
            assertEquals("17", redis.opsForValue().get("dish:stock:" + dishId));
        } finally { pool.shutdownNow(); }
    }
}
