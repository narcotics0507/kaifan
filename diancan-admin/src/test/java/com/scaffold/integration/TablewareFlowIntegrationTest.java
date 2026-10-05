package com.scaffold.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.DiancanAdminApplication;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.h5.H5Service;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.service.DiningTableService;
import com.scaffold.modules.dish.entity.Dish;
import com.scaffold.modules.dish.entity.DishCategory;
import com.scaffold.modules.dish.service.DishService;
import com.scaffold.modules.dish.service.DishCategoryService;
import com.scaffold.modules.order.dto.*;
import com.scaffold.modules.order.entity.OrderOperationLog;
import com.scaffold.modules.order.mapper.OrderOperationLogMapper;
import com.scaffold.modules.order.service.OrderService;
import com.scaffold.modules.order.vo.OrderVO;
import com.scaffold.modules.payment.dto.CashPayDTO;
import com.scaffold.modules.payment.service.PaymentService;
import com.scaffold.modules.report.service.RevenueLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.mockito.Mockito;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes = DiancanAdminApplication.class, properties = {
        "restaurant.h5.enabled=true", "restaurant.h5.signing-key=tableware-isolated-test-key-64-characters-never-for-real-use",
        "restaurant.h5.public-origin=http://localhost", "restaurant.tableware.require-guest-count=true" })
@ActiveProfiles("test") @AutoConfigureMockMvc
class TablewareFlowIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired H5Service h5;
    @Autowired DiningTableService tables; @Autowired DishService dishes; @Autowired DishCategoryService categories;
    @Autowired OrderService orders; @Autowired PaymentService payments; @Autowired OrderOperationLogMapper logs; @Autowired RevenueLedgerService ledger;
    @MockBean WsService websocket;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT = new java.util.concurrent.atomic.AtomicInteger(1);
    record Fixture(DiningTable table, Dish dish) {}
    record Visit(String token, String session) {}
    JsonNode call(String method, String path, String token, Object body) throws Exception {
        var builder = switch (method) { case "POST" -> post(path); case "PUT" -> put(path); default -> get(path); };
        if (path.equals("/app/h5/session")) builder.with(request -> { request.setRemoteAddr("192.0.2." + (CLIENT.getAndIncrement() % 250 + 1)); return request; });
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(builder).andReturn().getResponse().getContentAsString());
    }
    JsonNode ok(String method, String path, String token, Object body) throws Exception {
        JsonNode response = call(method, path, token, body); assertEquals(200, response.path("code").asInt(), response.toString()); return response.path("data");
    }
    Fixture fixture() {
        DishCategory c = new DishCategory(); c.setName("餐具验收分类" + UUID.randomUUID()); c.setStatus(1); c.setSort(1); categories.save(c);
        Dish d = new Dish(); d.setName("餐具验收菜" + UUID.randomUUID()); d.setCategoryId(c.getId()); d.setPrice(new BigDecimal("22")); d.setStock(-1); d.setSoldOut(0); d.setStatus(1); dishes.save(d);
        DiningTable t = new DiningTable(); t.setCode("TW" + UUID.randomUUID().toString().substring(0, 8)); t.setName("餐具验收桌"); t.setCapacity(8); t.setStatus(0); tables.save(t); return new Fixture(t, d);
    }
    Visit visit(Fixture f) throws Exception {
        String token = ok("POST", "/app/h5/session", null, null).path("token").asText();
        JsonNode table = ok("POST", "/app/h5/join", token, Map.of("tableCode", f.table.getCode(), "key", h5.signature(f.table)));
        return new Visit(token, table.path("currentSessionCode").asText());
    }
    void cart(Fixture f, Visit v, int quantity) throws Exception { ok("POST", "/app/cart/item?tableId=" + f.table.getId(), v.token, Map.of("dishId", f.dish.getId(), "quantity", quantity)); }
    Map<String, Object> payload(Fixture f, Visit v, String id, Object guests) {
        Map<String, Object> body = new HashMap<>(); body.put("tableId", f.table.getId()); body.put("sessionCode", v.session); body.put("requestId", id); body.put("remark", "餐具验收");
        if (guests != null) body.put("guestCount", guests); return body;
    }
    JsonNode submit(Fixture f, Visit v, String id, Object guests) throws Exception { return ok("POST", "/app/h5/submit", v.token, payload(f, v, id, guests)); }
    <T> T admin(Supplier<T> action) {
        try (var mock = Mockito.mockStatic(StpUtil.class)) {
            mock.when(StpUtil::getLoginIdAsLong).thenReturn(1L); mock.when(StpUtil::getLoginIdAsString).thenReturn("1"); return action.get();
        }
    }
    void eq(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    TablewareUpdateDTO adjustment(int people, int count, String id) { TablewareUpdateDTO dto = new TablewareUpdateDTO(); dto.setGuestCount(people); dto.setQuantity(count); dto.setReason("新增客人，核实餐具数量"); dto.setRequestId(id); return dto; }

    @Test void firstSubmissionRequiresIntegralPeopleAndDoesNotOpenTableOnFailure() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); String id = UUID.randomUUID().toString();
        for (Object guests : Arrays.asList(null, 0, 100, 4.5)) assertNotEquals(200, call("POST", "/app/h5/submit", v.token, payload(f, v, id, guests)).path("code").asInt());
        assertEquals(0, tables.getById(f.table.getId()).getStatus()); assertEquals(0, orders.count(new LambdaQueryWrapper<com.scaffold.modules.order.entity.Order>().eq(com.scaffold.modules.order.entity.Order::getTableId, f.table.getId())));
        JsonNode first = submit(f, v, id, 4); assertEquals(26, first.path("actualAmount").asInt()); assertEquals(4, first.path("tablewareQuantity").asInt()); assertEquals(4, tables.getByCode(f.table.getCode()).getGuestCount());
    }
    @Test void missingPeopleCannotConsumeFiniteRedisInventory() throws Exception {
        Fixture f = fixture(); f.dish.setStock(2); dishes.updateById(f.dish); Visit v = visit(f); cart(f, v, 1); String requestId = UUID.randomUUID().toString();
        assertNotEquals(200, call("POST", "/app/h5/submit", v.token, payload(f, v, requestId, null)).path("code").asInt());
        assertEquals(2, dishes.getById(f.dish.getId()).getStock());
        String stock = redis.opsForValue().get("dish:stock:" + f.dish.getId()); assertTrue(stock == null || stock.equals("2"));
        submit(f, v, requestId, 4);
        assertEquals(1, dishes.getById(f.dish.getId()).getStock()); assertEquals("1", redis.opsForValue().get("dish:stock:" + f.dish.getId()));
    }
    @Test void oneVisitChargesOnceAcrossVisitorsAdditionAndNetworkRetry() throws Exception {
        Fixture f = fixture(); Visit a = visit(f), b = visit(f); assertEquals(0, tables.getById(f.table.getId()).getStatus());
        cart(f, a, 1); String id = UUID.randomUUID().toString(); JsonNode first = submit(f, a, id, 4); JsonNode retry = submit(f, a, id, 4); assertEquals(first.path("id"), retry.path("id"));
        cart(f, b, 2); JsonNode add = submit(f, b, UUID.randomUUID().toString(), null); assertEquals(first.path("id"), add.path("id")); assertEquals(70, add.path("actualAmount").asInt()); assertEquals(4, add.path("tablewareAmount").asInt());
        assertEquals(2, add.path("items").size()); assertEquals(3, add.path("items").get(0).path("quantity").asInt() + add.path("items").get(1).path("quantity").asInt());
        var tickets = logs.selectList(new LambdaQueryWrapper<OrderOperationLog>().eq(OrderOperationLog::getOrderId, Long.valueOf(first.path("id").asText())).in(OrderOperationLog::getOperationType, "TICKET_ORDER", "TICKET_ADD"));
        assertEquals(2, tickets.size()); assertTrue(tickets.stream().noneMatch(l -> l.getDetail().contains("一次性餐具")));
    }
    @Test void concurrentFirstVisitorsCannotDuplicateTablewareCharge() throws Exception {
        Fixture f = fixture(); Visit a = visit(f), b = visit(f); cart(f, a, 1); cart(f, b, 1); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Future<JsonNode> x = pool.submit(() -> { start.await(); return submit(f, a, UUID.randomUUID().toString(), 4); });
            Future<JsonNode> y = pool.submit(() -> { start.await(); return submit(f, b, UUID.randomUUID().toString(), 4); }); start.countDown();
            assertEquals(x.get(20, TimeUnit.SECONDS).path("id"), y.get(20, TimeUnit.SECONDS).path("id"));
            JsonNode bill = ok("GET", "/app/order/table/" + f.table.getId(), a.token, null).get(0); assertEquals(48, bill.path("actualAmount").asInt()); assertEquals(4, bill.path("tablewareAmount").asInt());
        } finally { pool.shutdownNow(); }
    }
    @Test void differingVisitorCountCannotOverwriteConfirmedCount() throws Exception {
        Fixture f = fixture(); Visit a = visit(f), b = visit(f); cart(f, a, 1); submit(f, a, UUID.randomUUID().toString(), 4); cart(f, b, 1);
        assertNotEquals(200, call("POST", "/app/h5/submit", b.token, payload(f, b, UUID.randomUUID().toString(), 5)).path("code").asInt());
        JsonNode result = submit(f, b, UUID.randomUUID().toString(), null); assertEquals(4, result.path("guestCount").asInt()); assertEquals(48, result.path("actualAmount").asInt());
    }
    @Test void adjustmentChargesOnlyDifferenceAndIsIdempotentAndAudited() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        TablewareUpdateDTO change = adjustment(5, 5, UUID.randomUUID().toString()); OrderVO after = admin(() -> orders.updateTableware(id, change)); eq("27", after.getActualAmount()); eq("5", after.getTablewareAmount());
        eq("27", admin(() -> orders.updateTableware(id, change)).getActualAmount()); assertEquals(1, logs.selectCount(new LambdaQueryWrapper<OrderOperationLog>().eq(OrderOperationLog::getOrderId, id).eq(OrderOperationLog::getOperationType, "TABLEWARE")));
        assertThrows(BusinessException.class, () -> admin(() -> orders.updateTableware(id, adjustment(6, 6, change.getRequestId()))));
        assertEquals(403, call("PUT", "/admin/order/" + id + "/tableware", v.token, change).path("code").asInt());
    }
    @Test void foodDiscountAndWaiverNeverDiscountTableware() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        OrderDiscountDTO discount = new OrderDiscountDTO(); discount.setDiscountRate(new BigDecimal("0.50")); discount.setReason("验收菜品折扣"); eq("15", admin(() -> orders.discountOrder(id, discount)).getActualAmount());
        var bill = orders.getOrderDetail(id); KitchenWaiveDTO waive = new KitchenWaiveDTO(); waive.setReason("验收菜品免单"); waive.setRequestId(UUID.randomUUID().toString());
        OrderVO waived = admin(() -> orders.kitchenWaiveItem(bill.getItems().get(0).getId(), waive)); eq("4", waived.getActualAmount()); assertEquals(0, waived.getStatus());
    }
    @Test void allFoodReturnedStillLeavesOnlyUsedTablewareToCollect() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        ShortageReturnDTO change = new ShortageReturnDTO(); change.setQuantity(1); change.setRequestId(UUID.randomUUID().toString()); change.setNotifyKitchen(false);
        OrderVO remaining = admin(() -> orders.shortageReturn(orders.getOrderDetail(id).getItems().get(0).getId(), change)); eq("4", remaining.getActualAmount()); assertEquals(0, remaining.getStatus()); assertTrue(remaining.getItems().isEmpty());
        CashPayDTO receipt = new CashPayDTO(); receipt.setOrderId(id); receipt.setReceivedAmount(new BigDecimal("4")); receipt.setPaymentMethod(3); admin(() -> payments.cashPay(receipt)); assertEquals(0, tables.getById(f.table.getId()).getStatus());
    }
    @Test void settlementClearsPeopleAndNextVisitHasOwnCharge() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        CashPayDTO receipt = new CashPayDTO(); receipt.setOrderId(id); receipt.setReceivedAmount(new BigDecimal("30")); receipt.setPaymentMethod(2); eq("4", admin(() -> payments.cashPay(receipt)).getChangeAmount());
        assertThrows(BusinessException.class, () -> admin(() -> orders.updateTableware(id, adjustment(5, 5, UUID.randomUUID().toString())))); assertEquals(0, tables.getById(f.table.getId()).getStatus()); assertEquals(0, tables.getByCode(f.table.getCode()).getGuestCount());
        Visit next = visit(f); assertNotEquals(v.session, next.session); cart(f, next, 1); JsonNode bill = submit(f, next, UUID.randomUUID().toString(), 2); assertEquals(24, bill.path("actualAmount").asInt()); assertEquals(2, bill.path("tablewareQuantity").asInt());
    }
    @Test void waiterFirstOrderRequiresPeopleAndUsesSameChargePolicy() {
        Fixture f = fixture(); AdminOrderCreateDTO dto = new AdminOrderCreateDTO(); dto.setTableId(f.table.getId()); dto.setPaymentMode(1);
        AdminOrderCreateDTO.AdminOrderItemDTO item = new AdminOrderCreateDTO.AdminOrderItemDTO(); item.setDishId(f.dish.getId()); item.setQuantity(1); dto.setItems(List.of(item));
        assertThrows(BusinessException.class, () -> admin(() -> orders.createAdminOrder(dto))); assertEquals(0, tables.getById(f.table.getId()).getStatus());
        dto.setGuestCount(4); OrderVO bill = admin(() -> orders.createAdminOrder(dto)); eq("26", bill.getActualAmount()); assertEquals(4, bill.getGuestCount());
    }
    @Test void zeroValueVisitWithoutReceiptsCanResumeTablewareBilling() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        admin(() -> orders.updateTableware(id, adjustment(4, 0, UUID.randomUUID().toString())));
        KitchenWaiveDTO waive = new KitchenWaiveDTO(); waive.setReason("验收全菜免单"); waive.setRequestId(UUID.randomUUID().toString());
        admin(() -> orders.kitchenWaiveItem(orders.getOrderDetail(id).getItems().get(0).getId(), waive));
        assertEquals(1, orders.getById(id).getStatus());
        OrderVO resumed = admin(() -> orders.updateTableware(id, adjustment(5, 1, UUID.randomUUID().toString())));
        assertEquals(0, resumed.getStatus()); eq("1", resumed.getActualAmount()); eq("1", resumed.getTablewareAmount());
    }
    @Test void ledgerAndWorkbookSeparateTablewareFromFoodAndPreserveAmounts() throws Exception {
        Fixture f = fixture(); Visit v = visit(f); cart(f, v, 1); Long id = Long.valueOf(submit(f, v, UUID.randomUUID().toString(), 4).path("id").asText());
        var before = ledger.detail(LocalDate.now()).getOrders().stream().filter(b -> b.getId().equals(id.toString())).findFirst().orElseThrow(); eq("26", before.getOriginalAmount()); eq("0", before.getDiscountAmount()); eq("4", before.getTablewareAmount());
        MockHttpServletResponse response = new MockHttpServletResponse(); ledger.export(LocalDate.now(), LocalDate.now(), response);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals(7, book.getNumberOfSheets()); var sheet = book.getSheet("餐具明细"); boolean found = false;
            for (var row : sheet) if (row.getRowNum() > 0 && orders.getById(id).getOrderNo().equals(row.getCell(0).getStringCellValue())) { found = true; assertEquals(4, row.getCell(4).getNumericCellValue()); assertEquals(1, row.getCell(5).getNumericCellValue()); assertEquals(4, row.getCell(6).getNumericCellValue()); }
            assertTrue(found);
        }
    }
}
