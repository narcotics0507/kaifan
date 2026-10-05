package com.scaffold.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.DiancanAdminApplication;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.modules.cart.dto.CartItemDTO;
import com.scaffold.modules.cart.service.CartService;
import com.scaffold.modules.cart.vo.CartVO;
import com.scaffold.modules.coupon.service.CouponService;
import com.scaffold.modules.dish.dto.DishCategoryCreateDTO;
import com.scaffold.modules.dish.dto.DishCategoryUpdateDTO;
import com.scaffold.modules.dish.dto.DishUpdateDTO;
import com.scaffold.modules.dish.dto.DishQueryDTO;
import com.scaffold.modules.order.dto.AdminOrderEstimateDTO;
import com.scaffold.modules.order.dto.AddItemDTO;
import com.scaffold.modules.order.dto.ReplaceItemDTO;
import com.scaffold.framework.websocket.WsService;
import java.util.UUID;
import com.scaffold.modules.dish.dto.DishCreateDTO;
import com.scaffold.modules.dish.entity.Dish;
import com.scaffold.modules.dish.entity.DishCategory;
import com.scaffold.modules.dish.service.DishCategoryService;
import com.scaffold.modules.dish.service.DishService;
import com.scaffold.modules.kitchen.service.KitchenService;
import com.scaffold.modules.member.service.MemberSettlementService;
import com.scaffold.modules.order.dto.OrderCreateDTO;
import com.scaffold.modules.order.dto.AdminOrderCreateDTO;
import com.scaffold.modules.order.service.OrderService;
import com.scaffold.modules.order.vo.OrderItemVO;
import com.scaffold.modules.order.vo.OrderVO;
import com.scaffold.modules.payment.dto.AAPayDTO;
import com.scaffold.modules.payment.dto.CashPayDTO;
import com.scaffold.modules.payment.service.PaymentService;
import com.scaffold.modules.payment.vo.CashPayVO;
import com.scaffold.modules.table.dto.TableCreateDTO;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.service.DiningTableService;
import com.scaffold.modules.table.vo.DiningTableVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 19.1 堂食正餐完整流程集成测试：
 * 扫码开台 -> 浏览菜品 -> 加入购物车 -> 提交订单 ->
 * 后厨接单/划单 -> 小程序支付后继续加菜 -> 管理端结台 -> 标记清洁恢复空闲
 */
@SpringBootTest(classes = DiancanAdminApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DineInFlowIntegrationTest {

    private MockedStatic<StpUtil> stpUtilMock;

    @MockBean
    private WsService wsService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DishCategoryService dishCategoryService;

    @Autowired
    private DishService dishService;

    @Autowired
    private DiningTableService diningTableService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private KitchenService kitchenService;

    @Autowired
    private PaymentService paymentService;

    @MockBean
    private CouponService couponService;

    @MockBean
    private MemberSettlementService memberSettlementService;

    /**
     * 初始化小程序用户登录态
     *
     * @author Henfon
     * @date 2026-07-13
     * @description 集成测试直接调用小程序订单服务时，固定返回测试用户ID，避免依赖真实请求上下文。
     */
    @BeforeEach
    void setUpLoginContext() {
        stpUtilMock = Mockito.mockStatic(StpUtil.class);
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
        stpUtilMock.when(StpUtil::getLoginIdAsString).thenReturn("1");
    }

    /**
     * 清理小程序用户登录态
     *
     * @author Henfon
     * @date 2026-07-13
     * @description 每个测试结束后释放静态模拟，避免影响同一测试进程中的其他用例。
     */
    @AfterEach
    void tearDownLoginContext() {
        if (stpUtilMock != null) {
            stpUtilMock.close();
        }
    }

    @Test
    void dineInFullFlow_shouldWorkEndToEnd() {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String openid = "it-openid-" + suffix;

        // 1) 初始化测试数据：分类、菜品、桌台
        DishCategoryCreateDTO categoryDTO = new DishCategoryCreateDTO();
        categoryDTO.setName("IT分类-" + suffix);
        categoryDTO.setSort(1);
        dishCategoryService.createCategory(categoryDTO);

        DishCategory category = dishCategoryService.list().stream()
                .filter(c -> ("IT分类-" + suffix).equals(c.getName()))
                .findFirst()
                .orElseThrow();

        DishCreateDTO dishDTO = new DishCreateDTO();
        dishDTO.setCategoryId(category.getId());
        dishDTO.setName("IT菜品-" + suffix);
        dishDTO.setPrice(new BigDecimal("28.00"));
        dishDTO.setImage("/test/image.jpg");
        dishDTO.setThumbnail("/test/thumb.jpg");
        dishDTO.setSpiceLevel(1);
        dishDTO.setIngredients("[\"土豆\",\"牛肉\"]");
        dishDTO.setDescription("测试菜品");
        dishDTO.setStock(-1);
        dishDTO.setPreparationTime(10);
        dishService.createDish(dishDTO);

        Dish dish = dishService.list().stream()
                .filter(d -> ("IT菜品-" + suffix).equals(d.getName()))
                .findFirst()
                .orElseThrow();

        TableCreateDTO tableDTO = new TableCreateDTO();
        tableDTO.setCode("IT" + suffix.substring(Math.max(0, suffix.length() - 6)));
        tableDTO.setName("IT桌-" + suffix);
        tableDTO.setCapacity(4);
        tableDTO.setAreaName("测试区");
        diningTableService.createTable(tableDTO);

        DiningTable tableEntity = diningTableService.list().stream()
                .filter(t -> ("IT桌-" + suffix).equals(t.getName()))
                .findFirst()
                .orElseThrow();
        Long tableId = tableEntity.getId();

        // 2) 扫码开台（按 code 获取桌台 + 开台）
        DiningTableVO scannedTable = diningTableService.getByCode(tableDTO.getCode());
        assertEquals(tableId, scannedTable.getId());
        assertEquals(0, scannedTable.getStatus());

        diningTableService.openTable(tableId);
        DiningTable openedTable = diningTableService.getById(tableId);
        assertNotNull(openedTable);
        assertEquals(1, openedTable.getStatus());

        // 3) 浏览菜品：分类列表、在售列表、搜索
        assertTrue(
                dishCategoryService.listEnabled().stream().anyMatch(c -> c.getId().equals(category.getId())),
                "分类应在启用列表中"
        );

        Map<Long, List<com.scaffold.modules.dish.vo.DishListVO>> grouped = dishService.listOnSaleDishes();
        assertTrue(grouped.containsKey(category.getId()), "在售菜品应包含测试分类");
        assertTrue(grouped.get(category.getId()).stream().anyMatch(d -> d.getId().equals(dish.getId())), "在售菜品应包含测试菜品");

        assertTrue(
                dishService.searchDishes("IT菜品-" + suffix).stream().anyMatch(d -> d.getId().equals(dish.getId())),
                "搜索应返回测试菜品"
        );

        // 4) 加入购物车
        CartItemDTO cartItemDTO = new CartItemDTO();
        cartItemDTO.setDishId(dish.getId());
        cartItemDTO.setQuantity(2);
        cartItemDTO.setRemark("少辣");
        CartVO cart = cartService.addItem(openid, tableId, cartItemDTO);
        assertEquals(2, cart.getTotalCount());
        assertEquals(0, new BigDecimal("56.00").compareTo(cart.getTotalPrice()));

        // 5) 提交订单
        OrderCreateDTO orderCreateDTO = new OrderCreateDTO();
        orderCreateDTO.setTableId(tableId);
        orderCreateDTO.setPaymentMode(1); // 餐后付
        orderCreateDTO.setOrderType(0);   // 堂食
        orderCreateDTO.setRemark("集成测试订单");
        OrderVO order = orderService.createOrder(openid, orderCreateDTO);

        assertNotNull(order.getId());
        assertEquals(0, order.getStatus());
        assertEquals(tableId, order.getTableId());
        assertNotNull(order.getItems());
        assertFalse(order.getItems().isEmpty());
        assertTrue(order.getItems().stream().allMatch(i -> i.getStatus() == 0), "下单后订单项应为待制作");
        assertThrows(BusinessException.class, () -> diningTableService.releaseTable(tableId),
                "当前桌次已有订单时不得直接释放占用桌台");

        // 6) 后厨接单与划单
        Long firstItemId = order.getItems().get(0).getId();
        kitchenService.acceptTask(firstItemId);
        kitchenService.completeTask(firstItemId);

        OrderVO afterKitchen = orderService.getOrderDetail(order.getId());
        assertTrue(afterKitchen.getItems().stream().anyMatch(i -> i.getId().equals(firstItemId) && i.getStatus() == 2));

        // 若有多条订单项，全部划单完成
        for (OrderItemVO item : afterKitchen.getItems()) {
            if (item.getStatus() == 0) {
                kitchenService.acceptTask(item.getId());
                kitchenService.completeTask(item.getId());
            } else if (item.getStatus() == 1) {
                kitchenService.completeTask(item.getId());
            }
        }
        OrderVO allCompleted = orderService.getOrderDetail(order.getId());
        assertTrue(allCompleted.getItems().stream().allMatch(i -> i.getStatus() == 2), "应全部出餐完成");

        // 7) 先保留首单未结账，再创建同桌加菜单。
        // 8) 同一桌次再次下单，验证加菜订单与首单共享桌次且不会被已支付状态阻断。
        CartItemDTO addedCartItemDTO = new CartItemDTO();
        addedCartItemDTO.setDishId(dish.getId());
        addedCartItemDTO.setQuantity(1);
        addedCartItemDTO.setRemark("加菜");
        cartService.addItem(openid, tableId, addedCartItemDTO);

        OrderCreateDTO addedOrderDTO = new OrderCreateDTO();
        addedOrderDTO.setTableId(tableId);
        addedOrderDTO.setPaymentMode(1);
        addedOrderDTO.setOrderType(0);
        addedOrderDTO.setRemark("集成测试加菜订单");
        OrderVO addedOrder = orderService.createOrder(openid, addedOrderDTO);
        assertEquals(order.getTableSessionCode(), addedOrder.getTableSessionCode(), "加菜订单应归属同一桌次");
        assertFalse(diningTableService.checkoutTableIfSettled(tableId), "存在待支付加菜订单时不允许结台");
        assertEquals(1, diningTableService.getById(tableId).getStatus(), "未结清时桌台应保持占用");

        // 微信人工确认首单：还有加菜单未结清，不能提前清台。
        CashPayDTO firstReceipt = new CashPayDTO();
        firstReceipt.setOrderId(order.getId());
        firstReceipt.setPaymentMethod(3);
        firstReceipt.setReceivedAmount(order.getActualAmount());
        CashPayVO firstPayment = paymentService.cashPay(firstReceipt);
        assertEquals(3, firstPayment.getPaymentMethod());
        assertFalse(firstPayment.getTableCleared());
        assertEquals(1, diningTableService.getById(tableId).getStatus());
        assertThrows(BusinessException.class, () -> paymentService.cashPay(firstReceipt), "重复确认不得重复入账");

        // 支付宝收款码人工确认最后一单：金额必须一致，整桌结清后自动清台。
        CashPayDTO finalReceipt = new CashPayDTO();
        finalReceipt.setOrderId(addedOrder.getId());
        finalReceipt.setPaymentMethod(4);
        finalReceipt.setReceivedAmount(addedOrder.getActualAmount().add(BigDecimal.ONE));
        assertThrows(BusinessException.class, () -> paymentService.cashPay(finalReceipt));
        finalReceipt.setReceivedAmount(addedOrder.getActualAmount());
        CashPayVO finalPayment = paymentService.cashPay(finalReceipt);
        assertEquals(4, finalPayment.getPaymentMethod());
        assertTrue(finalPayment.getTableCleared());
        assertEquals(1, orderService.getOrderDetail(addedOrder.getId()).getStatus());
        assertEquals(0, diningTableService.getById(tableId).getStatus());
        assertNull(diningTableService.getById(tableId).getCurrentSessionCode());
        verifyNoInteractions(memberSettlementService, couponService);

        // 下一桌绑定时没有旧账、旧购物车，生成新的桌次；现金结账仍支持找零。
        DiningTableVO nextTable = diningTableService.bindCurrentUser(tableId, openid);
        assertNotEquals(order.getTableSessionCode(), nextTable.getCurrentSessionCode());
        assertTrue(orderService.getTableOrders(tableId).isEmpty());
        assertTrue(cartService.getCart(openid, tableId).getItems().isEmpty());
        cartService.addItem(openid, tableId, addedCartItemDTO);
        OrderVO nextOrder = orderService.createOrder(openid, addedOrderDTO);
        CashPayDTO cash = new CashPayDTO();
        cash.setOrderId(nextOrder.getId());
        cash.setReceivedAmount(nextOrder.getActualAmount().add(new BigDecimal("10.00")));
        CashPayVO cashResult = paymentService.cashPay(cash);
        assertEquals(2, cashResult.getPaymentMethod());
        assertEquals(0, new BigDecimal("10.00").compareTo(cashResult.getChangeAmount()));
        assertTrue(cashResult.getTableCleared());
        assertEquals(0, diningTableService.getById(tableId).getStatus());

    }

    /**
     * 验证占用空桌可以直接释放
     *
     * @author Henfon
     * @date 2026-07-13
     * @description 模拟顾客扫码开台但未提交订单，管理端释放后应清空桌态和当前桌次。
     */
    @Test
    void releaseOccupiedTableWithoutOrders_shouldReturnToFree() {
        final String suffix = String.valueOf(System.currentTimeMillis());

        TableCreateDTO tableDTO = new TableCreateDTO();
        tableDTO.setCode("ER" + suffix.substring(Math.max(0, suffix.length() - 6)));
        tableDTO.setName("空占用桌-" + suffix);
        tableDTO.setCapacity(4);
        diningTableService.createTable(tableDTO);

        DiningTable table = diningTableService.list().stream()
                .filter(item -> tableDTO.getCode().equals(item.getCode()))
                .findFirst()
                .orElseThrow();
        diningTableService.openTable(table.getId());

        DiningTable occupiedTable = diningTableService.getById(table.getId());
        assertEquals(1, occupiedTable.getStatus(), "扫码开台后应为占用状态");
        assertNotNull(occupiedTable.getCurrentSessionCode(), "扫码开台后应生成当前桌次");

        diningTableService.releaseTable(table.getId());
        DiningTable releasedTable = diningTableService.getById(table.getId());
        assertEquals(0, releasedTable.getStatus(), "未产生订单的占用桌应允许释放");
        assertNull(releasedTable.getCurrentSessionCode(), "释放空桌后应清空当前桌次");
    }

    @Test
    void adminCreatePreOrder_shouldCreateIndependentOrders() {
        final String suffix = String.valueOf(System.currentTimeMillis());

        DishCategoryCreateDTO categoryDTO = new DishCategoryCreateDTO();
        categoryDTO.setName("幂等分类-" + suffix);
        dishCategoryService.createCategory(categoryDTO);
        DishCategory category = dishCategoryService.list().stream()
                .filter(c -> ("幂等分类-" + suffix).equals(c.getName()))
                .findFirst()
                .orElseThrow();

        DishCreateDTO dishDTO = new DishCreateDTO();
        dishDTO.setCategoryId(category.getId());
        dishDTO.setName("幂等菜品-" + suffix);
        dishDTO.setPrice(new BigDecimal("18.00"));
        dishDTO.setImage("/test/i.jpg");
        dishDTO.setThumbnail("/test/t.jpg");
        dishDTO.setStock(-1);
        dishDTO.setPreparationTime(8);
        dishService.createDish(dishDTO);
        Dish dish = dishService.list().stream()
                .filter(d -> ("幂等菜品-" + suffix).equals(d.getName()))
                .findFirst()
                .orElseThrow();

        TableCreateDTO tableDTO = new TableCreateDTO();
        tableDTO.setCode("IDM" + suffix.substring(Math.max(0, suffix.length() - 5)));
        tableDTO.setName("幂等桌-" + suffix);
        tableDTO.setCapacity(4);
        diningTableService.createTable(tableDTO);
        DiningTable table = diningTableService.list().stream()
                .filter(t -> ("幂等桌-" + suffix).equals(t.getName()))
                .findFirst()
                .orElseThrow();

        AdminOrderCreateDTO dto = new AdminOrderCreateDTO();
        dto.setTableId(table.getId());
        dto.setTableCode(table.getCode());
        dto.setPaymentMode(1);
        dto.setOrderType(0);
        dto.setPreOrder(true);

        AdminOrderCreateDTO.AdminOrderItemDTO item = new AdminOrderCreateDTO.AdminOrderItemDTO();
        item.setDishId(dish.getId());
        item.setQuantity(1);
        dto.setItems(List.of(item));

        OrderVO first = orderService.createAdminOrder(dto);
        OrderVO second = orderService.createAdminOrder(dto);

        assertNotNull(first.getId());
        assertNotNull(second.getId());
        assertNotEquals(first.getId(), second.getId(), "当前管理端预订单重复提交应生成独立订单");
        assertNotEquals(first.getOrderNo(), second.getOrderNo(), "当前管理端预订单会生成新的订单编号");
    }

    @Test
    void bindCurrentUser_shouldKeepSharedSessionUntilLastMemberLeaves() {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String firstOpenid = "bind-openid-a-" + suffix;
        final String secondOpenid = "bind-openid-b-" + suffix;
        final String nextOpenid = "bind-openid-c-" + suffix;

        DishCategoryCreateDTO categoryDTO = new DishCategoryCreateDTO();
        categoryDTO.setName("绑定桌次分类-" + suffix);
        dishCategoryService.createCategory(categoryDTO);
        DishCategory category = dishCategoryService.list().stream()
                .filter(c -> ("绑定桌次分类-" + suffix).equals(c.getName()))
                .findFirst()
                .orElseThrow();

        DishCreateDTO dishDTO = new DishCreateDTO();
        dishDTO.setCategoryId(category.getId());
        dishDTO.setName("绑定桌次菜品-" + suffix);
        dishDTO.setPrice(new BigDecimal("12.00"));
        dishDTO.setImage("/test/bind-i.jpg");
        dishDTO.setThumbnail("/test/bind-t.jpg");
        dishDTO.setStock(-1);
        dishService.createDish(dishDTO);
        Dish dish = dishService.list().stream()
                .filter(d -> ("绑定桌次菜品-" + suffix).equals(d.getName()))
                .findFirst()
                .orElseThrow();

        TableCreateDTO fromTableDTO = new TableCreateDTO();
        fromTableDTO.setCode("BF" + suffix.substring(Math.max(0, suffix.length() - 5)));
        fromTableDTO.setName("绑定原桌-" + suffix);
        fromTableDTO.setCapacity(4);
        diningTableService.createTable(fromTableDTO);

        TableCreateDTO toTableDTO = new TableCreateDTO();
        toTableDTO.setCode("BT" + suffix.substring(Math.max(0, suffix.length() - 5)));
        toTableDTO.setName("绑定目标桌-" + suffix);
        toTableDTO.setCapacity(4);
        diningTableService.createTable(toTableDTO);

        TableCreateDTO anotherTableDTO = new TableCreateDTO();
        anotherTableDTO.setCode("BU" + suffix.substring(Math.max(0, suffix.length() - 5)));
        anotherTableDTO.setName("绑定后续桌-" + suffix);
        anotherTableDTO.setCapacity(4);
        diningTableService.createTable(anotherTableDTO);

        DiningTable fromTable = diningTableService.list().stream()
                .filter(t -> ("绑定原桌-" + suffix).equals(t.getName()))
                .findFirst()
                .orElseThrow();
        DiningTable toTable = diningTableService.list().stream()
                .filter(t -> ("绑定目标桌-" + suffix).equals(t.getName()))
                .findFirst()
                .orElseThrow();
        DiningTable anotherTable = diningTableService.list().stream()
                .filter(t -> ("绑定后续桌-" + suffix).equals(t.getName()))
                .findFirst()
                .orElseThrow();

        DiningTableVO firstBind = diningTableService.bindCurrentUser(fromTable.getId(), firstOpenid);
        DiningTableVO secondBind = diningTableService.bindCurrentUser(fromTable.getId(), secondOpenid);
        String originalSessionCode = firstBind.getCurrentSessionCode();
        assertNotNull(originalSessionCode, "首次绑定后应生成桌次编码");
        assertEquals(originalSessionCode, secondBind.getCurrentSessionCode(), "同桌第二位顾客应加入同一桌次");

        CartItemDTO firstCartItem = new CartItemDTO();
        firstCartItem.setDishId(dish.getId());
        firstCartItem.setQuantity(1);
        cartService.addItem(firstOpenid, fromTable.getId(), firstCartItem);

        OrderCreateDTO orderCreateDTO = new OrderCreateDTO();
        orderCreateDTO.setTableId(fromTable.getId());
        orderCreateDTO.setPaymentMode(1);
        orderCreateDTO.setOrderType(0);
        OrderVO originalOrder = orderService.createOrder(firstOpenid, orderCreateDTO);
        assertEquals(fromTable.getId(), originalOrder.getTableId());
        assertEquals(originalSessionCode, originalOrder.getTableSessionCode());

        CartItemDTO secondCartItem = new CartItemDTO();
        secondCartItem.setDishId(dish.getId());
        secondCartItem.setQuantity(2);
        secondCartItem.setRemark("原桌未提交菜品");
        cartService.addItem(firstOpenid, fromTable.getId(), secondCartItem);

        DiningTableVO firstRebind = diningTableService.bindCurrentUser(toTable.getId(), firstOpenid);
        DiningTable afterFirstLeave = diningTableService.getById(fromTable.getId());
        assertEquals(1, afterFirstLeave.getStatus(), "原桌仍有同桌顾客时，不应提前释放");
        assertEquals(originalSessionCode, afterFirstLeave.getCurrentSessionCode(), "仍有人在桌时应保留原桌次");
        assertEquals(1, orderService.getTableOrders(fromTable.getId()).size(), "仍有人在桌时原桌应继续返回当前桌次订单");

        OrderVO storedOrder = orderService.getOrderDetail(originalOrder.getId());
        assertEquals(fromTable.getId(), storedOrder.getTableId(), "顾客换到新桌后，旧订单不应迁移到新桌");
        assertEquals(originalSessionCode, storedOrder.getTableSessionCode(), "旧订单应继续保留在原桌次");
        assertNotEquals(originalSessionCode, firstRebind.getCurrentSessionCode(), "新桌应开启独立桌次");

        CartVO newTableCart = cartService.getCart(firstOpenid, toTable.getId());
        assertEquals(0, newTableCart.getTotalCount(), "新桌购物车应从空开始，不继承原桌未提交菜品");

        diningTableService.bindCurrentUser(anotherTable.getId(), secondOpenid);

        DiningTable releasedFromTable = diningTableService.getById(fromTable.getId());
        assertEquals(1, releasedFromTable.getStatus(), "已有订单时，最后一位顾客换桌不能清台");
        assertEquals(originalSessionCode, releasedFromTable.getCurrentSessionCode(), "未结账订单必须保留原桌次");
        CashPayDTO leaveReceipt = new CashPayDTO();
        leaveReceipt.setOrderId(originalOrder.getId());
        leaveReceipt.setReceivedAmount(originalOrder.getActualAmount());
        leaveReceipt.setPaymentMethod(3);
        paymentService.cashPay(leaveReceipt);
        assertEquals(0, diningTableService.getById(fromTable.getId()).getStatus(), "结清后才结束原桌次");
        assertTrue(orderService.getTableOrders(fromTable.getId()).isEmpty(), "原桌空出后，新客不应再看到上一批客人的订单");

        DiningTableVO reopenedFromTable = diningTableService.bindCurrentUser(fromTable.getId(), nextOpenid);
        assertNotNull(reopenedFromTable.getCurrentSessionCode(), "新客重新入桌时应生成新的桌次编码");
        assertNotEquals(originalSessionCode, reopenedFromTable.getCurrentSessionCode(), "新一批客人应拿到新的桌次编码");

        CartVO nextCustomerCart = cartService.getCart(nextOpenid, fromTable.getId());
        assertEquals(0, nextCustomerCart.getTotalCount(), "新客人的购物车应为空");
    }
    @Test
    void disabledFeatures_shouldRejectOldApiAndPromotionRequests() throws Exception {
        for (String path : List.of("/app/member/center", "/app/coupon/my", "/admin/member/list", "/admin/coupon/list")) {
            mockMvc.perform(get(path)).andExpect(jsonPath("$.message").value("本店已停用此功能"));
        }
        for (String path : List.of("/app/payment/wechat", "/app/payment/alipay", "/app/payment/aa",
                "/admin/payment/qrcode", "/admin/payment/split-bill", "/wx/pay/notify")) {
            mockMvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.message").value("本店已停用此功能"));
        }
        AAPayDTO aa = new AAPayDTO();
        aa.setOrderId(1L);
        aa.setAmount(BigDecimal.ONE);
        assertThrows(BusinessException.class, () -> paymentService.aaPay("unused", aa));
        OrderCreateDTO promotionalOrder = new OrderCreateDTO();
        promotionalOrder.setCouponId(1L);
        assertThrows(BusinessException.class, () -> orderService.createOrder("unused", promotionalOrder));
        promotionalOrder.setCouponId(null);
        promotionalOrder.setUsePoints(1);
        assertThrows(BusinessException.class, () -> orderService.createOrder("unused", promotionalOrder));
    }

    @Test
    void kitchenSellingState_shouldReflectGlobalDishChangesWithoutCancellingAcceptedItems() {
        MenuFixture f = menuFixture();
        OrderVO order = orderService.createAdminOrder(merchantMenuOrder(f));
        Long itemId = order.getItems().get(0).getId();
        assertEquals(0, kitchenService.getTaskList().stream().filter(task -> task.getId().equals(itemId)).findFirst().orElseThrow().getSoldOut());
        dishService.markSoldOut(f.dish().getId(), 1);
        assertEquals(1, kitchenService.getTaskList().stream().filter(task -> task.getId().equals(itemId)).findFirst().orElseThrow().getSoldOut());
        assertEquals(new BigDecimal("12.00"), orderService.getOrderDetail(order.getId()).getActualAmount());
        assertEquals(1, orderService.getOrderDetail(order.getId()).getItems().size());
        assertThrows(BusinessException.class, () -> dishService.requireOrderableDish(f.dish().getId()));
        dishService.markSoldOut(f.dish().getId(), 0);
        assertEquals(0, kitchenService.getTaskList().stream().filter(task -> task.getId().equals(itemId)).findFirst().orElseThrow().getSoldOut());
        assertNotNull(dishService.requireOrderableDish(f.dish().getId()));
    }

    @Autowired private com.scaffold.modules.print.service.KitchenPaperService kitchenPapers;

    private com.scaffold.modules.order.dto.ShortageReturnDTO shortage(int quantity,String request) {
        var dto=new com.scaffold.modules.order.dto.ShortageReturnDTO();dto.setQuantity(quantity);dto.setRequestId(request);return dto;
    }

    @Test
    void shortageReturn_partialAndRetryShouldOnlyAdjustTheSelectedBill() {
        MenuFixture f=menuFixture();var create=merchantMenuOrder(f);create.getItems().get(0).setQuantity(3);
        OrderVO order=orderService.createAdminOrder(create);Long itemId=order.getItems().get(0).getId();
        MenuFixture other=menuFixture();var otherCreate=merchantMenuOrder(other);otherCreate.getItems().get(0).setDishId(f.dish().getId());
        OrderVO otherOrder=orderService.createAdminOrder(otherCreate);int stockBefore=dishService.getById(f.dish().getId()).getStock();
        OrderVO result=orderService.shortageReturn(itemId,shortage(1,"same-request"));
        assertEquals(new BigDecimal("24.00"),result.getActualAmount());assertEquals(2,result.getItems().get(0).getQuantity());
        assertEquals(1,result.getReturnedItems().size());assertEquals(1,result.getReturnedItems().get(0).getQuantity());assertEquals(BigDecimal.ZERO,result.getReturnedItems().get(0).getAmount());
        assertEquals(1,dishService.getById(f.dish().getId()).getSoldOut());
        assertEquals(new BigDecimal("12.00"),orderService.getOrderDetail(otherOrder.getId()).getActualAmount());
        assertEquals(1,orderService.getOrderDetail(otherOrder.getId()).getItems().get(0).getQuantity());
        assertEquals(stockBefore+1,dishService.getById(f.dish().getId()).getStock());
        assertEquals(new BigDecimal("24.00"),orderService.shortageReturn(itemId,shortage(1,"same-request")).getActualAmount());
        assertEquals(stockBefore+1,dishService.getById(f.dish().getId()).getStock());
        assertThrows(BusinessException.class,()->orderService.shortageReturn(itemId,shortage(2,"same-request")));
        assertEquals(1,kitchenPapers.list(order.getId()).size(),"Kitchen-originated return must not print another warning to itself");
        assertFalse(merchantMenuContains(f,true));
    }

    @Test
    void shortageReturn_shouldPreserveDiscountAndRejectAlreadyPaidBills() {
        MenuFixture f=menuFixture();var create=merchantMenuOrder(f);create.getItems().get(0).setQuantity(3);OrderVO order=orderService.createAdminOrder(create);Long item=order.getItems().get(0).getId();
        com.scaffold.modules.order.dto.OrderDiscountDTO discount=new com.scaffold.modules.order.dto.OrderDiscountDTO();discount.setDiscountRate(new BigDecimal("0.80"));discount.setReason("八折");orderService.discountOrder(order.getId(),discount);
        assertEquals(new BigDecimal("19.20"),orderService.shortageReturn(item,shortage(1,"discount-return")).getActualAmount());
        CashPayDTO pay=new CashPayDTO();pay.setOrderId(order.getId());pay.setReceivedAmount(new BigDecimal("19.20"));paymentService.cashPay(pay);
        assertThrows(BusinessException.class,()->orderService.shortageReturn(item,shortage(1,"paid-return")));
        assertEquals(new BigDecimal("19.20"),orderService.shortageReturn(item,shortage(1,"discount-return")).getActualAmount(),"Lost-response retry after settlement must not refund again");
        assertFalse(kitchenService.getCurrentBillIds().contains(order.getId()));
    }

    @Test
    void shortageReturn_fullReturnShouldPreserveZeroChargeHistoryAndAllowNewSelection() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));
        OrderVO result=orderService.shortageReturn(order.getItems().get(0).getId(),shortage(1,"all-return"));
        assertEquals(2,result.getStatus());assertTrue(result.getItems().isEmpty());assertEquals(0,result.getActualAmount().compareTo(BigDecimal.ZERO));
        assertEquals(1,result.getReturnedItems().size());assertTrue(orderService.getTableOrders(f.table().getId()).stream().anyMatch(o->o.getId().equals(order.getId())&&o.getReturnedItems().size()==1));
        assertFalse(kitchenService.getCurrentBillIds().contains(order.getId()));
        dishService.markSoldOut(f.dish().getId(),0);
        OrderVO next=orderService.createAdminOrder(merchantMenuOrder(f));assertNotEquals(order.getId(),next.getId());assertEquals(order.getTableSessionCode(),next.getTableSessionCode());
        assertTrue(kitchenService.getCurrentBillIds().contains(next.getId()));
        assertTrue(kitchenPapers.receipt(orderService.getById(order.getId()),List.of()).contains("已退，不收费 ￥0.00"));
    }

    @Test
    void shortageReturn_invalidQuantityMustNotChangeMoneyOrAvailability() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));int before=dishService.getById(f.dish().getId()).getStock();
        assertThrows(BusinessException.class,()->orderService.shortageReturn(order.getItems().get(0).getId(),shortage(2,"too-many")));
        assertEquals(new BigDecimal("12.00"),orderService.getOrderDetail(order.getId()).getActualAmount());assertEquals(0,dishService.getById(f.dish().getId()).getSoldOut());assertEquals(before,dishService.getById(f.dish().getId()).getStock());assertTrue(orderService.getOrderDetail(order.getId()).getReturnedItems().isEmpty());
    }

    @Test
    void frontdeskReturnAndReplacementShouldCreateOnlyChangeTickets() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));
        com.scaffold.modules.order.dto.ReturnItemDTO dto=new com.scaffold.modules.order.dto.ReturnItemDTO();dto.setAuthPassword("123456");dto.setReason("客人改菜");
        orderService.returnItem(order.getItems().get(0).getId(),dto);
        assertEquals(2,kitchenPapers.list(order.getId()).size());assertTrue(kitchenPapers.list(order.getId()).get(1).getText().contains("取消，不再制作"));
        MenuFixture next=menuFixture(),replacement=menuFixture();OrderVO replaceOrder=orderService.createAdminOrder(merchantMenuOrder(next));
        ReplaceItemDTO replace=new ReplaceItemDTO();replace.setAuthPassword("123456");replace.setNewDishId(replacement.dish().getId());replace.setQuantity(1);replace.setReason("换口味");
        orderService.replaceItem(replaceOrder.getItems().get(0).getId(),replace);
        assertEquals(2,kitchenPapers.list(replaceOrder.getId()).size(),"A replacement prints one change notice, not an extra full order");assertTrue(kitchenPapers.list(replaceOrder.getId()).get(1).getText().contains("改做："));
    }

    @Test
    void addPaperItems_shouldBeGroupedWithinOneOrderTransaction() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));
        org.springframework.transaction.support.TransactionTemplate transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status->{AddItemDTO add=new AddItemDTO();add.setDishId(f.dish().getId());add.setQuantity(1);orderService.addItem(order.getId(),add);orderService.addItem(order.getId(),add);});
        assertEquals(2,kitchenPapers.list(order.getId()).size(),"Two additions in one committed cart must form one add ticket");assertEquals("TICKET_ADD",kitchenPapers.list(order.getId()).get(1).getType());
        var added=kitchenPapers.list(order.getId()).get(1).getItems();assertEquals(2,added.size());assertTrue(added.stream().allMatch(row->row.getQuantity()==1 && row.getDishName().equals(f.dish().getName())));
    }

    @Test
    void voicePaperSnapshot_shouldNotIncludeRemarksOrChangeAfterReturn() {
        MenuFixture f=menuFixture();var create=merchantMenuOrder(f);create.getItems().get(0).setQuantity(3);create.getItems().get(0).setRemark("少辣\n假菜 × 99");
        OrderVO order=orderService.createAdminOrder(create);var before=kitchenPapers.list(order.getId()).get(0).getItems();
        assertEquals(1,before.size());assertEquals(f.dish().getName(),before.get(0).getDishName());assertEquals(3,before.get(0).getQuantity());
        orderService.shortageReturn(order.getItems().get(0).getId(),shortage(1,"voice-snapshot-return"));
        assertEquals(2,orderService.getOrderDetail(order.getId()).getItems().get(0).getQuantity());assertEquals(3,kitchenPapers.list(order.getId()).get(0).getItems().get(0).getQuantity());
    }

    @Test
    void shortageReturn_zeroGiftedBillHasNoPaymentToRefund() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));Long id=order.getItems().get(0).getId();
        OrderVO gifted=orderService.giftItem(id);assertEquals(1,gifted.getStatus());assertTrue(gifted.getShortageAllowed());
        assertTrue(kitchenService.getCurrentBillIds().contains(order.getId()));
        OrderVO returned=orderService.shortageReturn(id,shortage(1,"gifted-shortage"));assertEquals(2,returned.getStatus());assertEquals(0,returned.getActualAmount().compareTo(BigDecimal.ZERO));assertEquals(1,returned.getReturnedItems().size());
    }

    @Test
    void shortageReturn_shouldUseAcceptedItemSnapshotAfterDishDeletion() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));
        dishService.removeById(f.dish().getId());
        OrderVO returned=orderService.shortageReturn(order.getItems().get(0).getId(),shortage(1,"deleted-dish"));
        assertEquals(0,returned.getActualAmount().compareTo(BigDecimal.ZERO));assertEquals(f.dish().getName(),returned.getReturnedItems().get(0).getDishName());
    }

    @Test
    void shortageReturn_partlyReceivedBillMustBeHandledByCashier() {
        MenuFixture f=menuFixture();OrderVO result=orderService.createAdminOrder(merchantMenuOrder(f));
        com.scaffold.modules.order.entity.Order order=orderService.getById(result.getId());order.setPaidAmount(new BigDecimal("6.00"));orderService.updateById(order);
        assertThrows(BusinessException.class,()->orderService.shortageReturn(result.getItems().get(0).getId(),shortage(1,"partly-paid")));
        assertEquals(new BigDecimal("12.00"),orderService.getOrderDetail(order.getId()).getActualAmount());assertEquals(0,dishService.getById(f.dish().getId()).getSoldOut());
    }

    @Test
    void shortageReturn_frontdeskDelegateShouldOnlyNotifyTheCanceledQuantityOnce() {
        MenuFixture f=menuFixture();var create=merchantMenuOrder(f);create.getItems().get(0).setQuantity(3);OrderVO order=orderService.createAdminOrder(create);
        var dto=shortage(1,"delegate-notice");dto.setNotifyKitchen(true);Long id=order.getItems().get(0).getId();
        orderService.shortageReturn(id,dto);assertEquals(2,kitchenPapers.list(order.getId()).size());
        assertTrue(kitchenPapers.list(order.getId()).get(1).getText().contains("取消，不再制作："+f.dish().getName()+" × 1"));
        orderService.shortageReturn(id,dto);assertEquals(2,kitchenPapers.list(order.getId()).size());assertEquals(new BigDecimal("24.00"),orderService.getOrderDetail(order.getId()).getActualAmount());
    }

    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private record MenuFixture(DishCategory category, Dish dish, DiningTable table, String openid) {}

    private MenuFixture menuFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        DishCategoryCreateDTO categoryDTO = new DishCategoryCreateDTO();
        categoryDTO.setName("菜单分类-" + suffix);
        categoryDTO.setStatus(1);
        dishCategoryService.createCategory(categoryDTO);
        DishCategory category = dishCategoryService.lambdaQuery().eq(DishCategory::getName, categoryDTO.getName()).one();
        DishCreateDTO dishDTO = new DishCreateDTO();
        dishDTO.setCategoryId(category.getId());
        dishDTO.setName("菜单菜品-" + suffix);
        dishDTO.setPrice(new BigDecimal("12.00"));
        dishDTO.setStock(10);
        dishService.createDish(dishDTO);
        Dish dish = dishService.lambdaQuery().eq(Dish::getName, dishDTO.getName()).one();
        TableCreateDTO tableDTO = new TableCreateDTO();
        tableDTO.setCode("MT" + suffix);
        tableDTO.setName("菜单验收桌-" + suffix);
        tableDTO.setCapacity(4);
        diningTableService.createTable(tableDTO);
        DiningTable table = diningTableService.lambdaQuery().eq(DiningTable::getCode, tableDTO.getCode()).one();
        String openid = "menu-it-" + suffix;
        diningTableService.bindCurrentUser(table.getId(), openid);
        return new MenuFixture(category, dish, table, openid);
    }

    private void categoryState(MenuFixture f, int status) {
        DishCategoryUpdateDTO dto = new DishCategoryUpdateDTO();
        dto.setId(f.category().getId());
        dto.setStatus(status);
        dishCategoryService.updateCategory(dto);
    }

    private CartItemDTO menuCartItem(MenuFixture f, int quantity) {
        CartItemDTO item = new CartItemDTO();
        item.setDishId(f.dish().getId());
        item.setQuantity(quantity);
        return item;
    }

    private OrderCreateDTO customerMenuOrder(MenuFixture f) {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setTableId(f.table().getId());
        dto.setPaymentMode(1);
        return dto;
    }

    private AdminOrderCreateDTO merchantMenuOrder(MenuFixture f) {
        AdminOrderCreateDTO dto = new AdminOrderCreateDTO();
        dto.setTableId(f.table().getId());
        dto.setTableCode(f.table().getCode());
        dto.setPaymentMode(1);
        AdminOrderCreateDTO.AdminOrderItemDTO item = new AdminOrderCreateDTO.AdminOrderItemDTO();
        item.setDishId(f.dish().getId());
        item.setQuantity(1);
        dto.setItems(List.of(item));
        return dto;
    }

    private boolean merchantMenuContains(MenuFixture f, boolean orderableOnly) {
        DishQueryDTO query = new DishQueryDTO();
        query.setOrderableOnly(orderableOnly);
        query.setName(f.dish().getName());
        return dishService.listDishesForAdmin(1, 20, query).getRecords().stream().anyMatch(d -> d.getId().equals(f.dish().getId()));
    }

    private void assertMenuUnavailable(MenuFixture f, String reason) throws Exception {
        assertFalse(dishService.listOnSaleDishes().containsKey(f.category().getId()));
        assertTrue(dishService.searchDishes(f.dish().getName()).isEmpty());
        assertFalse(merchantMenuContains(f, true));
        assertTrue(merchantMenuContains(f, false), "后台管理仍能查看和重新分类菜品");
        assertEquals(f.dish().getName(), dishService.getDishDetail(f.dish().getId()).getName());
        mockMvc.perform(get("/app/dish/" + f.dish().getId()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(reason)));
        BusinessException error = assertThrows(BusinessException.class, () -> dishService.requireOrderableDish(f.dish().getId()));
        assertTrue(error.getMessage().contains(reason));
        assertThrows(BusinessException.class, () -> cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 1)));
        BusinessException createError = assertThrows(BusinessException.class, () -> orderService.createAdminOrder(merchantMenuOrder(f)));
        assertTrue(createError.getMessage().contains(reason));
        AdminOrderEstimateDTO estimate = new AdminOrderEstimateDTO();
        AdminOrderEstimateDTO.AdminOrderEstimateItemDTO item = new AdminOrderEstimateDTO.AdminOrderEstimateItemDTO();
        item.setDishId(f.dish().getId()); item.setQuantity(1); estimate.setItems(List.of(item));
        assertThrows(BusinessException.class, () -> orderService.estimateAdminOrder(estimate));
    }

    @Test
    void disabledCategory_shouldBlockEveryOrderingEntryAndReenableImmediately() throws Exception {
        MenuFixture f = menuFixture();
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 2));
        assertTrue(dishService.listOnSaleDishes().containsKey(f.category().getId()), "先建立缓存");
        assertTrue(merchantMenuContains(f, true));
        categoryState(f, 0);
        assertMenuUnavailable(f, "分类已停用");
        CartVO staleCart = cartService.getCart(f.openid(), f.table().getId());
        assertTrue(staleCart.getHasUnavailableItems());
        assertEquals(2, staleCart.getItems().get(0).getQuantity(), "不能静默删除顾客已选菜品");
        assertFalse(staleCart.getItems().get(0).getAvailable());
        assertThrows(BusinessException.class, () -> cartService.updateItemQuantity(f.openid(), f.table().getId(), f.dish().getId(), 3));
        assertThrows(BusinessException.class, () -> orderService.createOrder(f.openid(), customerMenuOrder(f)));
        assertEquals(10, dishService.getById(f.dish().getId()).getStock());
        assertEquals(0, orderService.lambdaQuery().eq(com.scaffold.modules.order.entity.Order::getTableId, f.table().getId()).count());
        // 减少/移除不可点菜品仍被允许，恢复分类后可再次点单，不能等待10分钟缓存过期。
        cartService.updateItemQuantity(f.openid(), f.table().getId(), f.dish().getId(), 1);
        categoryState(f, 1);
        assertTrue(dishService.listOnSaleDishes().containsKey(f.category().getId()));
        assertTrue(merchantMenuContains(f, true));
        assertFalse(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
        assertNotNull(orderService.createOrder(f.openid(), customerMenuOrder(f)).getId());
    }

    @Test
    void deletedCategory_shouldKeepDishForManagementAndRequireReclassification() throws Exception {
        MenuFixture f = menuFixture();
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 1));
        dishService.listOnSaleDishes();
        dishCategoryService.deleteCategory(f.category().getId());
        assertMenuUnavailable(f, "分类已删除");
        assertThrows(BusinessException.class, () -> orderService.createOrder(f.openid(), customerMenuOrder(f)));
        assertTrue(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
        assertNotNull(dishService.getById(f.dish().getId()));
        // 把仍保留的菜品移到有效分类后可恢复点单。
        MenuFixture active = menuFixture();
        DishUpdateDTO change = new DishUpdateDTO();
        change.setId(f.dish().getId()); change.setCategoryId(active.category().getId());
        dishService.updateDish(change);
        assertNotNull(dishService.requireOrderableDish(f.dish().getId()));
        assertFalse(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
        assertNotNull(orderService.createOrder(f.openid(), customerMenuOrder(f)).getId());
    }

    @Test
    void existingOrder_shouldSurviveCategoryDeletionAndRejectAddOrReplace() {
        MenuFixture f = menuFixture();
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 1));
        OrderVO original = orderService.createOrder(f.openid(), customerMenuOrder(f));
        int originalStock = dishService.getById(f.dish().getId()).getStock();
        dishCategoryService.deleteCategory(f.category().getId());
        AddItemDTO add = new AddItemDTO(); add.setDishId(f.dish().getId()); add.setQuantity(1);
        assertThrows(BusinessException.class, () -> orderService.addItem(original.getId(), add));
        ReplaceItemDTO replace = new ReplaceItemDTO();
        replace.setNewDishId(f.dish().getId()); replace.setQuantity(1); replace.setAuthPassword("123456"); replace.setReason("分类删除回归测试");
        assertThrows(BusinessException.class, () -> orderService.replaceItem(original.getItems().get(0).getId(), replace));
        OrderVO unchanged = orderService.getOrderDetail(original.getId());
        assertEquals(original.getItems().get(0).getId(), unchanged.getItems().get(0).getId());
        assertEquals(original.getActualAmount(), unchanged.getActualAmount());
        assertEquals(originalStock, dishService.getById(f.dish().getId()).getStock(), "失败换菜必须回滚原菜品和库存");
        CashPayDTO receipt = new CashPayDTO(); receipt.setOrderId(original.getId()); receipt.setPaymentMethod(3); receipt.setReceivedAmount(original.getActualAmount());
        assertTrue(paymentService.cashPay(receipt).getTableCleared());
        assertEquals(1, orderService.getOrderDetail(original.getId()).getStatus());
        assertEquals(0, diningTableService.getById(f.table().getId()).getStatus());
    }

    @Test
    void unsoldOrOutOfStockDish_shouldNotLeakThroughSearchAndOldCart() {
        MenuFixture f = menuFixture();
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 1));
        dishService.listOnSaleDishes();
        dishService.markSoldOut(f.dish().getId(), 1);
        assertTrue(dishService.searchDishes(f.dish().getName()).isEmpty());
        assertFalse(merchantMenuContains(f, true));
        assertTrue(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
        assertThrows(BusinessException.class, () -> orderService.createOrder(f.openid(), customerMenuOrder(f)));
        dishService.markSoldOut(f.dish().getId(), 0);
        dishService.updateDishStatus(f.dish().getId(), 0);
        assertFalse(merchantMenuContains(f, true));
        assertTrue(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
        assertThrows(BusinessException.class, () -> orderService.createOrder(f.openid(), customerMenuOrder(f)));
        dishService.updateDishStatus(f.dish().getId(), 1);
        DishUpdateDTO stock = new DishUpdateDTO(); stock.setId(f.dish().getId()); stock.setStock(0); dishService.updateDish(stock);
        assertTrue(dishService.searchDishes(f.dish().getName()).isEmpty());
        assertFalse(merchantMenuContains(f, true));
        assertThrows(BusinessException.class, () -> orderService.createOrder(f.openid(), customerMenuOrder(f)));
        cartService.removeItem(f.openid(), f.table().getId(), f.dish().getId());
        assertFalse(cartService.getCart(f.openid(), f.table().getId()).getHasUnavailableItems());
    }

    private Dish extraDish(MenuFixture f, String price) {
        DishCreateDTO dto = new DishCreateDTO();
        dto.setCategoryId(f.category().getId());
        dto.setName("上线验收菜-" + UUID.randomUUID());
        dto.setPrice(new BigDecimal(price));
        dto.setStock(100);
        dishService.createDish(dto);
        return dishService.lambdaQuery().eq(Dish::getName, dto.getName()).one();
    }

    private OrderVO fixtureOrder(MenuFixture f, int quantity) {
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, quantity));
        return orderService.createOrder(f.openid(), customerMenuOrder(f));
    }

    @Test
    void replaceGiftDiscountAndAdd_shouldKeepBillStockAndKitchenConsistent() {
        MenuFixture f = menuFixture();
        Dish replacement = extraDish(f, "15.00");
        OrderVO original = fixtureOrder(f, 2);
        ReplaceItemDTO replace = new ReplaceItemDTO();
        replace.setNewDishId(replacement.getId()); replace.setQuantity(1);
        replace.setAuthPassword("wrong"); replace.setReason("上线验收换菜");
        Long oldItemId = original.getItems().get(0).getId();
        assertThrows(BusinessException.class, () -> orderService.replaceItem(oldItemId, replace));
        assertEquals(new BigDecimal("24.00"), orderService.getOrderDetail(original.getId()).getActualAmount());
        boolean oldAutoAccept = kitchenService.isAutoAcceptEnabled();
        try {
            kitchenService.updateAutoAcceptEnabled(true);
            replace.setAuthPassword("123456");
            OrderVO changed = orderService.replaceItem(oldItemId, replace);
            Long newItemId = changed.getItems().get(0).getId();
            assertNotEquals(oldItemId, newItemId);
            assertEquals(10, dishService.getById(f.dish().getId()).getStock());
            assertEquals(99, dishService.getById(replacement.getId()).getStock());
            assertEquals(new BigDecimal("15.00"), changed.getActualAmount());
            assertEquals(1, orderService.getOrderDetail(original.getId()).getItems().get(0).getStatus(), "换菜的新菜也必须按后厨自动接单配置处理");
            assertFalse(kitchenService.getTaskList().stream().anyMatch(task -> task.getId().equals(oldItemId)));
            assertTrue(kitchenService.getTaskList().stream().anyMatch(task -> task.getId().equals(newItemId)));
            AddItemDTO add = new AddItemDTO(); add.setDishId(f.dish().getId()); add.setQuantity(2);
            assertEquals(new BigDecimal("39.00"), orderService.addItem(original.getId(), add).getActualAmount());
            assertEquals(new BigDecimal("24.00"), orderService.giftItem(newItemId).getActualAmount());
            assertEquals(new BigDecimal("24.00"), orderService.giftItem(newItemId).getActualAmount(), "重复赠送不可再次扣减");
            assertEquals(99, dishService.getById(replacement.getId()).getStock(), "赠送的菜仍实际出餐，不回补库存");
            assertEquals(1L, orderService.getAdminOrderDetail(original.getId()).getOperationLogs().stream()
                    .filter(op -> "GIFT".equals(op.getOperationType())).count(), "重复赠送不能重复记日志");
            com.scaffold.modules.order.dto.OrderDiscountDTO discount = new com.scaffold.modules.order.dto.OrderDiscountDTO();
            discount.setDiscountRate(new BigDecimal("0.80")); discount.setReason("上线验收八折");
            assertEquals(new BigDecimal("19.20"), orderService.discountOrder(original.getId(), discount).getActualAmount());
            add.setDishId(replacement.getId()); add.setQuantity(1);
            assertEquals(new BigDecimal("31.20"), orderService.addItem(original.getId(), add).getActualAmount(), "加菜后应继续保持人工折扣");
            CashPayDTO receipt = new CashPayDTO(); receipt.setOrderId(original.getId()); receipt.setPaymentMethod(2); receipt.setReceivedAmount(new BigDecimal("40.00"));
            assertEquals(new BigDecimal("8.80"), paymentService.cashPay(receipt).getChangeAmount());
            assertEquals(0, diningTableService.getById(f.table().getId()).getStatus());
            assertThrows(BusinessException.class, () -> orderService.discountOrder(original.getId(), discount));
            assertThrows(BusinessException.class, () -> orderService.addItem(original.getId(), add));
        } finally {
            kitchenService.updateAutoAcceptEnabled(oldAutoAccept);
        }
    }

    @Test
    void changeTable_shouldMoveBillBindingsAndCartWithoutLeakingPreviousSession() {
        MenuFixture f = menuFixture();
        OrderVO original = fixtureOrder(f, 2);
        cartService.addItem(f.openid(), f.table().getId(), menuCartItem(f, 1));
        TableCreateDTO target = new TableCreateDTO();
        target.setCode("MOVE" + UUID.randomUUID().toString().substring(0, 8)); target.setName("换桌验收"); target.setCapacity(4);
        diningTableService.createTable(target);
        DiningTable to = diningTableService.lambdaQuery().eq(DiningTable::getCode, target.getCode()).one();
        MenuFixture occupied = menuFixture();
        assertThrows(BusinessException.class, () -> diningTableService.changeTable(f.table().getId(), occupied.table().getId()));
        assertEquals(f.table().getId(), orderService.getOrderDetail(original.getId()).getTableId());
        diningTableService.changeTable(f.table().getId(), to.getId());
        OrderVO moved = orderService.getOrderDetail(original.getId());
        assertEquals(to.getId(), moved.getTableId()); assertEquals(target.getCode(), moved.getTableCode());
        assertEquals(original.getTableSessionCode(), moved.getTableSessionCode());
        assertEquals(new BigDecimal("24.00"), moved.getActualAmount());
        assertEquals(0, diningTableService.getById(f.table().getId()).getStatus());
        assertNull(diningTableService.getById(f.table().getId()).getCurrentSessionCode());
        assertTrue(orderService.getTableOrders(f.table().getId()).isEmpty());
        assertEquals(1, cartService.getCart(f.openid(), to.getId()).getTotalCount());
        assertEquals(0, cartService.getCart(f.openid(), f.table().getId()).getTotalCount());
        DiningTableVO rebound = diningTableService.bindCurrentUser(to.getId(), f.openid());
        assertEquals(original.getTableSessionCode(), rebound.getCurrentSessionCode());
        CashPayDTO receipt = new CashPayDTO(); receipt.setOrderId(original.getId()); receipt.setPaymentMethod(3); receipt.setReceivedAmount(new BigDecimal("24.00"));
        assertTrue(paymentService.cashPay(receipt).getTableCleared());
        assertEquals(0, cartService.getCart(f.openid(), to.getId()).getTotalCount());
        assertEquals(0, diningTableService.getById(to.getId()).getStatus());
    }

    @Test
    void simultaneousAdditions_shouldNotLoseItemsMoneyOrStock() throws Exception {
        MenuFixture f = menuFixture();
        OrderVO original = fixtureOrder(f, 1);
        List<Dish> dishes = java.util.stream.IntStream.range(0, 6).mapToObj(i -> extraDish(f, String.valueOf(13 + i))).toList();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (Dish dish : dishes) {
                futures.add(pool.submit(() -> {
                    try (MockedStatic<StpUtil> login = Mockito.mockStatic(StpUtil.class)) {
                        login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                        login.when(StpUtil::getLoginIdAsString).thenReturn("1");
                        start.await();
                        for (int i = 0; i < 4; i++) {
                            AddItemDTO add = new AddItemDTO(); add.setDishId(dish.getId()); add.setQuantity(1);
                            orderService.addItem(original.getId(), add);
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        OrderVO finalBill = orderService.getOrderDetail(original.getId());
        BigDecimal expected = new BigDecimal("384.00"); // 12 + 4 × (13+14+15+16+17+18)
        assertEquals(25, finalBill.getItems().size(), "所有请求各保留一个订单项");
        assertEquals(expected, finalBill.getItems().stream().map(OrderItemVO::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(expected, finalBill.getActualAmount(), "同桌并发加菜不能丢失账单金额");
        assertEquals(expected, finalBill.getOriginalAmount());
        for (Dish dish : dishes) assertEquals(96, dishService.getById(dish.getId()).getStock());
    }

    @Test
    void simultaneousReceipts_shouldRecordOnlyOneSettlement() throws Exception {
        MenuFixture f = menuFixture(); OrderVO original = fixtureOrder(f, 1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                try {
                    start.await(); CashPayDTO dto = new CashPayDTO(); dto.setOrderId(original.getId()); dto.setPaymentMethod(3); dto.setReceivedAmount(new BigDecimal("12.00"));
                    paymentService.cashPay(dto); success.incrementAndGet();
                } catch (BusinessException e) { rejected.incrementAndGet(); }
                  catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            start.countDown(); for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1, success.get()); assertEquals(1, rejected.get());
        assertEquals(1L, paymentService.lambdaQuery().eq(com.scaffold.modules.payment.entity.PaymentRecord::getOrderId, original.getId()).count());
        assertEquals(new BigDecimal("12.00"), orderService.getOrderDetail(original.getId()).getPaidAmount());
        assertEquals(0, diningTableService.getById(f.table().getId()).getStatus());
    }

    @Test
    void receiptRacingAddition_shouldNeverSettleAnOutdatedBill() throws Exception {
        for (int round = 0; round < 3; round++) {
            MenuFixture f = menuFixture(); OrderVO original = fixtureOrder(f, 1);
            Dish extra = extraDish(f, "13.00");
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var additions = new java.util.concurrent.atomic.AtomicInteger();
            var receipts = new java.util.concurrent.atomic.AtomicInteger();
            try {
                var addFuture = pool.submit(() -> {
                    try (MockedStatic<StpUtil> login = Mockito.mockStatic(StpUtil.class)) {
                        login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
                        login.when(StpUtil::getLoginIdAsString).thenReturn("1");
                        start.await(); AddItemDTO dto = new AddItemDTO(); dto.setDishId(extra.getId()); dto.setQuantity(1);
                        orderService.addItem(original.getId(), dto); additions.incrementAndGet();
                    } catch (BusinessException ignored) { }
                      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                });
                var payFuture = pool.submit(() -> {
                    try {
                        start.await(); CashPayDTO dto = new CashPayDTO(); dto.setOrderId(original.getId()); dto.setPaymentMethod(3); dto.setReceivedAmount(new BigDecimal("12.00"));
                        paymentService.cashPay(dto); receipts.incrementAndGet();
                    } catch (BusinessException ignored) { }
                      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                });
                start.countDown(); addFuture.get(30, java.util.concurrent.TimeUnit.SECONDS); payFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertEquals(1, additions.get() + receipts.get(), "收款与加菜互斥，旧金额不能收掉新账单");
            OrderVO bill = orderService.getOrderDetail(original.getId());
            long payments = paymentService.lambdaQuery().eq(com.scaffold.modules.payment.entity.PaymentRecord::getOrderId, original.getId()).count();
            if (receipts.get() == 1) {
                assertEquals(1, bill.getStatus()); assertEquals(new BigDecimal("12.00"), bill.getPaidAmount());
                assertEquals(1, bill.getItems().size()); assertEquals(1L, payments);
                assertEquals(100, dishService.getById(extra.getId()).getStock());
                assertEquals(0, diningTableService.getById(f.table().getId()).getStatus());
            } else {
                assertEquals(0, bill.getStatus()); assertEquals(new BigDecimal("25.00"), bill.getActualAmount());
                assertEquals(new BigDecimal("0.00"), bill.getPaidAmount()); assertEquals(2, bill.getItems().size());
                assertEquals(0L, payments); assertEquals(99, dishService.getById(extra.getId()).getStock());
                assertEquals(1, diningTableService.getById(f.table().getId()).getStatus());
            }
        }
    }

    @Autowired private com.scaffold.modules.print.service.KitchenSequenceService kitchenSequence;

    private com.scaffold.modules.order.dto.KitchenWaiveDTO waive(String id){var d=new com.scaffold.modules.order.dto.KitchenWaiveDTO();d.setRequestId(id);d.setReason("做错菜，门店免单");return d;}

    @Test void kitchenNumbersShouldOrderTwoTablesThenAddition() {
        MenuFixture a=menuFixture(),b=menuFixture();OrderVO one=orderService.createAdminOrder(merchantMenuOrder(a));OrderVO two=orderService.createAdminOrder(merchantMenuOrder(b));
        var first=kitchenPapers.list(one.getId()).get(0);var second=kitchenPapers.list(two.getId()).get(0);
        AddItemDTO add=new AddItemDTO();add.setDishId(a.dish().getId());add.setQuantity(2);orderService.addItem(one.getId(),add);var third=kitchenPapers.list(one.getId()).get(1);
        assertEquals(first.getQueueDate(),second.getQueueDate());assertEquals(first.getQueueNumber()+1,second.getQueueNumber());assertEquals(second.getQueueNumber()+1,third.getQueueNumber());
        assertEquals("TICKET_ADD",third.getType());assertEquals(one.getId(),third.getOrderId());assertTrue(third.getText().startsWith("厨房顺序："));assertEquals(1,third.getItems().size());assertEquals(2,third.getItems().get(0).getQuantity());assertNotNull(third.getItems().get(0).getOrderItemId());
        assertEquals(first.getQueueNumber(),kitchenPapers.list(one.getId()).get(0).getQueueNumber());
    }

    @Test void kitchenBatchAndReprintShouldNotConsumeExtraNumber() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));int n=kitchenPapers.list(order.getId()).get(0).getQueueNumber();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);tx.executeWithoutResult(status->{AddItemDTO d=new AddItemDTO();d.setDishId(f.dish().getId());d.setQuantity(1);orderService.addItem(order.getId(),d);orderService.addItem(order.getId(),d);});
        var batch=kitchenPapers.list(order.getId()).get(1);assertEquals(n+1,batch.getQueueNumber());assertEquals(2,batch.getItems().size());
        kitchenPapers.recordReprint(orderService.getById(order.getId()),List.of());assertNull(kitchenPapers.list(order.getId()).get(2).getQueueNumber());
        MenuFixture other=menuFixture();OrderVO next=orderService.createAdminOrder(merchantMenuOrder(other));assertEquals(n+2,kitchenPapers.list(next.getId()).get(0).getQueueNumber());
    }

    @Test void sequenceRollbackShouldNotConsumeACommittedNumber() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);int before=tx.execute(status->kitchenSequence.next().number());
        tx.executeWithoutResult(status->{kitchenSequence.next();status.setRollbackOnly();});int after=tx.execute(status->kitchenSequence.next().number());assertEquals(before+1,after);
    }

    @Test void concurrentSequenceTransactionsShouldHaveUniqueNumbers() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(6);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {var tasks=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();for(int i=0;i<6;i++)tasks.add(pool.submit(()->tx.execute(status->kitchenSequence.next().number())));var set=new java.util.HashSet<Integer>();for(var f:tasks)set.add(f.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(6,set.size());}finally{pool.shutdownNow();}
    }

    @Test void kitchenWaiverShouldPreserveFoodAndStockAndBeIdempotent() {
        MenuFixture f=menuFixture();var create=merchantMenuOrder(f);create.getItems().get(0).setQuantity(2);OrderVO order=orderService.createAdminOrder(create);Long item=order.getItems().get(0).getId();int stock=dishService.getById(f.dish().getId()).getStock();var paper=kitchenPapers.list(order.getId()).get(0);
        OrderVO result=orderService.kitchenWaiveItem(item,waive("waive-repeat"));assertEquals(0,result.getActualAmount().compareTo(BigDecimal.ZERO));assertEquals(2,result.getItems().get(0).getQuantity());assertEquals(1,result.getItems().get(0).getIsGift());assertEquals(1,result.getStatus());
        assertEquals(stock,dishService.getById(f.dish().getId()).getStock());assertEquals(0,dishService.getById(f.dish().getId()).getSoldOut());assertEquals(1,kitchenPapers.list(order.getId()).size());assertEquals(paper.getQueueNumber(),kitchenPapers.list(order.getId()).get(0).getQueueNumber());
        assertEquals(0,orderService.kitchenWaiveItem(item,waive("waive-repeat")).getActualAmount().compareTo(BigDecimal.ZERO));assertEquals(1,orderService.getAdminOrderDetail(order.getId()).getOperationLogs().stream().filter(log->"KITCHEN_WAIVE".equals(log.getOperationType())).count());
        assertTrue(kitchenPapers.receiptSnapshot(order.getId()).contains("免单，不收费"));assertEquals(1,diningTableService.getById(f.table().getId()).getStatus());
    }

    @Test void kitchenWaiverShouldKeepOtherFoodChargedAndPreserveDiscount() {
        MenuFixture f=menuFixture(),second=menuFixture();var create=merchantMenuOrder(f);var other=new AdminOrderCreateDTO.AdminOrderItemDTO();other.setDishId(second.dish().getId());other.setQuantity(1);create.setItems(new java.util.ArrayList<>(create.getItems()));create.getItems().add(other);OrderVO order=orderService.createAdminOrder(create);
        var discount=new com.scaffold.modules.order.dto.OrderDiscountDTO();discount.setDiscountRate(new BigDecimal("0.80"));discount.setReason("八折");orderService.discountOrder(order.getId(),discount);
        OrderVO result=orderService.kitchenWaiveItem(order.getItems().get(0).getId(),waive("one-food-only"));assertEquals(new BigDecimal("9.60"),result.getActualAmount());assertEquals(2,result.getItems().size());assertEquals(0,result.getStatus());
    }

    @Test void kitchenWaiverShouldRejectPaidAndEndedOrRepeatedConflict() {
        MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));var pay=new CashPayDTO();pay.setOrderId(order.getId());pay.setReceivedAmount(order.getActualAmount());paymentService.cashPay(pay);
        assertThrows(BusinessException.class,()->orderService.kitchenWaiveItem(order.getItems().get(0).getId(),waive("already-paid")));
        MenuFixture next=menuFixture();OrderVO zero=orderService.createAdminOrder(merchantMenuOrder(next));var d=waive("conflict");orderService.kitchenWaiveItem(zero.getItems().get(0).getId(),d);d.setReason("修改理由");assertThrows(BusinessException.class,()->orderService.kitchenWaiveItem(zero.getItems().get(0).getId(),d));
    }

    @Test void kitchenWaiverShouldRejectPartialReceiptsAndKeepChargeIntact() {MenuFixture f=menuFixture();OrderVO order=orderService.createAdminOrder(merchantMenuOrder(f));var entity=orderService.getById(order.getId());entity.setPaidAmount(new BigDecimal("1.00"));orderService.updateById(entity);assertThrows(BusinessException.class,()->orderService.kitchenWaiveItem(order.getItems().get(0).getId(),waive("part-paid")));assertEquals(new BigDecimal("12.00"),orderService.getOrderDetail(order.getId()).getActualAmount());assertEquals(0,orderService.getOrderDetail(order.getId()).getItems().get(0).getIsGift());}

    @Test void addingAfterFullWaiverShouldStillPrintAnAdditionForTheSameVisit() {MenuFixture f=menuFixture();OrderVO first=orderService.createAdminOrder(merchantMenuOrder(f));orderService.kitchenWaiveItem(first.getItems().get(0).getId(),waive("all-free-add"));OrderVO next=orderService.createAdminOrder(merchantMenuOrder(f));assertNotEquals(first.getId(),next.getId());assertEquals(first.getTableSessionCode(),next.getTableSessionCode());var a=kitchenPapers.list(first.getId()).get(0);var b=kitchenPapers.list(next.getId()).get(0);assertEquals("TICKET_ADD",b.getType());assertEquals(a.getQueueNumber()+1,b.getQueueNumber());}

}
