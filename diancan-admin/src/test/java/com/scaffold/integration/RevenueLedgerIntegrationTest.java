package com.scaffold.integration;

import com.scaffold.DiancanAdminApplication;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.order.entity.*;
import com.scaffold.modules.order.mapper.*;
import com.scaffold.modules.payment.entity.PaymentRecord;
import com.scaffold.modules.payment.mapper.PaymentRecordMapper;
import com.scaffold.modules.report.service.RevenueLedgerService;
import com.scaffold.modules.report.vo.RevenueDailyVO;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Financial-date, reconciliation and workbook tests on the isolated test database. */
@SpringBootTest(classes = DiancanAdminApplication.class)
@ActiveProfiles("test")
@Transactional
class RevenueLedgerIntegrationTest {
    @MockBean private WsService wsService;
    @Autowired private RevenueLedgerService ledger;
    @Autowired private OrderMapper orders;
    @Autowired private OrderItemMapper items;
    @Autowired private PaymentRecordMapper payments;
    @Autowired private OrderOperationLogMapper logs;
    private static final LocalDate DAY = LocalDate.of(2036, 8, 10);
    private BigDecimal amount(String value) { return new BigDecimal(value); }
    private void eq(String value, BigDecimal actual) { assertEquals(0, amount(value).compareTo(actual)); }
    private Order bill(LocalDateTime opened, String amount, int status) {
        Order order = new Order(); order.setOrderNo("RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        order.setTableCode("R01"); order.setTableSessionCode("REPORT-TEST"); order.setOriginalAmount(amount(amount)); order.setActualAmount(amount(amount));
        order.setPaidAmount(status == 0 ? BigDecimal.ZERO : amount(amount)); order.setStatus(status); order.setPaymentMode(1); order.setOrderType(0);
        order.setCreateTime(opened); order.setDeleted(0); orders.insert(order); return order;
    }
    private PaymentRecord receipt(Order order, LocalDateTime date, String amount, int method, int status) {
        PaymentRecord p = new PaymentRecord(); p.setOrderId(order.getId()); p.setPaymentNo("RP" + UUID.randomUUID().toString().replace("-", ""));
        p.setCreateTime(date); p.setAmount(amount(amount)); p.setPaymentMethod(method); p.setStatus(status); p.setDeleted(0); payments.insert(p); return p;
    }
    private void log(Order order, LocalDateTime date, String kind, String detail) {
        OrderOperationLog l = new OrderOperationLog(); l.setOrderId(order.getId()); l.setCreateTime(date); l.setOperationType(kind);
        l.setOperatorId(0L); l.setDetail(detail); l.setReason("验收原因"); l.setOperatorName("验收员工"); l.setDeleted(0); logs.insert(l);
    }
    private void item(Order order, String name, String price, int quantity, boolean gift) {
        OrderItem i = new OrderItem(); i.setOrderId(order.getId()); i.setDishId(1L); i.setDishName(name); i.setPrice(amount(price)); i.setQuantity(quantity);
        i.setAmount(gift ? BigDecimal.ZERO : amount(price).multiply(BigDecimal.valueOf(quantity))); i.setIsGift(gift ? 1 : 0);
        i.setCreateTime(order.getCreateTime()); i.setAddedAt(order.getCreateTime()); i.setDeleted(0); items.insert(i);
    }

    @Test void receiptDayIncludesCrossMidnightBillAndZeroDays() {
        Order order = bill(DAY.minusDays(1).atTime(23, 50), "96.30", 1);
        receipt(order, DAY.atStartOfDay(), "96.30", 2, 1);
        var days = ledger.daily(DAY.minusDays(1), DAY.plusDays(1)); assertEquals(3, days.size());
        eq("0", days.get(0).getTotalRevenue()); eq("96.30", days.get(1).getTotalRevenue()); eq("0", days.get(2).getTotalRevenue());
        assertEquals(1, days.get(1).getOrderCount()); assertEquals(0, days.get(1).getOpenedOrderCount());
        assertEquals(order.getOrderNo(), ledger.detail(DAY).getOrders().get(0).getOrderNo());
    }
    @Test void multipleReceiptsAreCountedOnceAndFailedPendingDeletedAreExcluded() {
        Order order = bill(DAY.atTime(10, 0), "40.15", 1);
        receipt(order, DAY.atTime(11, 0), "10.05", 3, 1); receipt(order, DAY.atTime(11, 1), "30.10", 4, 1);
        receipt(order, DAY.atTime(11, 2), "100", 2, 0); receipt(order, DAY.atTime(11, 3), "100", 2, 3);
        Order deleted = bill(DAY.atTime(10, 0), "999", 1); receipt(deleted, DAY.atTime(11, 0), "999", 2, 1); orders.deleteById(deleted.getId());
        RevenueDailyVO day = ledger.detail(DAY); eq("40.15", day.getReceivedAmount()); eq("40.15", day.getTotalRevenue());
        eq("10.05", day.getWechatAmount()); eq("30.10", day.getAlipayAmount()); assertEquals(1, day.getOrderCount()); assertEquals(2, day.getPayments().size());
    }
    @Test void laterRefundPreservesReceiptDayAndSubtractsOnRefundDay() {
        Order order = bill(DAY.minusDays(1).atTime(10, 0), "32.50", 3);
        receipt(order, DAY.minusDays(1).atTime(12, 0), "32.50", 3, 2);
        log(order, DAY.atTime(12, 0), "REFUND_ORDER", "{\"refundAmount\":32.50}");
        eq("32.50", ledger.detail(DAY.minusDays(1)).getTotalRevenue());
        RevenueDailyVO day = ledger.detail(DAY); eq("0", day.getReceivedAmount()); eq("32.50", day.getRefundAmount()); eq("-32.50", day.getTotalRevenue()); eq("-32.50", day.getWechatAmount());
        assertEquals(0, day.getOrderCount()); assertEquals("退款", day.getPayments().get(0).getKind()); eq("-32.50", day.getOrders().get(0).getDayNetAmount());
    }
    @Test void preSettlementReturnsAndWaiversAreNotDeductedTwice() {
        Order order = bill(DAY.atTime(10, 0), "20", 1); item(order, "保留菜", "20", 1, false); item(order, "免单菜", "12", 1, true);
        log(order, DAY.atTime(10, 10), "SHORTAGE_RETURN", "{\"dishName\":\"缺菜\",\"quantity\":1,\"amount\":18}");
        log(order, DAY.atTime(10, 20), "KITCHEN_WAIVE", "{\"dishName\":\"免单菜\",\"quantity\":1,\"amount\":12}");
        receipt(order, DAY.atTime(12, 0), "20", 2, 1);
        RevenueDailyVO day = ledger.detail(DAY); eq("20", day.getTotalRevenue()); eq("18", day.getReturnedAmount()); eq("12", day.getWaivedAmount());
        assertEquals(2, day.getAdjustments().size()); assertEquals(2, day.getOrders().get(0).getItems().size());
        eq("32", day.getOrders().get(0).getOriginalAmount()); eq("12", day.getOrders().get(0).getDiscountAmount());
    }
    @Test void unpaidAndZeroValueBillsAreVisibleButNeverCountAsReceipts() {
        Order unpaid = bill(DAY.atTime(10, 0), "88", 0); bill(DAY.atTime(10, 1), "0", 1);
        RevenueDailyVO day = ledger.detail(DAY); eq("0", day.getTotalRevenue()); eq("88", day.getUnsettledAmount()); assertEquals(0, day.getOrderCount());
        assertEquals(2, day.getOpenedOrderCount()); assertEquals(2, day.getOrders().size());
        eq("88", day.getOrders().stream().filter(b -> b.getId().equals(unpaid.getId().toString())).findFirst().orElseThrow().getUnsettledAmount());
    }
    @Test void midnightEndBoundaryAndIsoWeekYearAreCorrect() {
        LocalDate jan = LocalDate.of(2021, 1, 1); Order order = bill(jan.atStartOfDay(), "2", 1);
        receipt(order, jan.atTime(23, 59, 59), "1", 2, 1); receipt(order, jan.plusDays(1).atStartOfDay(), "1", 2, 1);
        eq("1", ledger.detail(jan).getTotalRevenue()); assertEquals("2020-W53", ledger.trend("week", jan, jan).get(0).getDate());
    }
    @Test void invalidRangesAreRejectedAndMalformedAuditAmountsStayUnknown() {
        assertThrows(BusinessException.class, () -> ledger.daily(DAY, DAY.minusDays(1)));
        assertThrows(BusinessException.class, () -> ledger.daily(DAY, DAY.plusDays(366)));
        assertThrows(BusinessException.class, () -> ledger.trend("invalid", DAY, DAY));
        Order order = bill(DAY.atTime(10, 0), "10", 0); log(order, DAY.atTime(11, 0), "RETURN", "bad-json");
        RevenueDailyVO day = ledger.detail(DAY); assertNull(day.getAdjustments().get(0).getAmount()); eq("0", day.getTotalRevenue());
        assertEquals(366, ledger.daily(DAY, DAY.plusDays(365)).size());
    }
    @Test void workbookIncludesRealLineItemsAndReconcilesSheetsWithoutDuplicateItems() throws Exception {
        Order order = bill(DAY.atTime(10, 0), "35.25", 1); item(order, "=菜名也作为文本", "11.75", 3, false);
        receipt(order, DAY.atTime(11, 0), "20.10", 3, 1); receipt(order, DAY.plusDays(1).atTime(11, 0), "15.15", 2, 1);
        log(order, DAY.atTime(10, 30), "RETURN", "{\"dishName\":\"退菜\",\"quantity\":1,\"amount\":5.50}");
        MockHttpServletResponse response = new MockHttpServletResponse(); ledger.export(DAY, DAY.plusDays(1), response);
        assertTrue(response.getHeader("Content-Disposition").contains("filename*=UTF-8"));
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals(7, book.getNumberOfSheets()); assertNotNull(book.getSheet("退菜免单记录")); assertNotNull(book.getSheet("统计说明"));
            var daily = book.getSheet("每日汇总"); assertEquals(4, daily.getPhysicalNumberOfRows());
            assertEquals(20.10, daily.getRow(1).getCell(3).getNumericCellValue(), .001); assertEquals(15.15, daily.getRow(2).getCell(3).getNumericCellValue(), .001);
            assertEquals("合计", daily.getRow(3).getCell(0).getStringCellValue());
            assertEquals(35.25, daily.getRow(3).getCell(3).getNumericCellValue(), .001);
            assertTrue(daily.getRow(1).getCell(3).getCellStyle().getDataFormatString().contains("0.00"));
            assertTrue(daily.getPaneInformation().isFreezePane());
            assertEquals(3, book.getSheet("订单明细").getPhysicalNumberOfRows());
            var itemSheet = book.getSheet("菜品明细"); assertEquals(2, itemSheet.getPhysicalNumberOfRows());
            assertEquals(CellType.STRING, itemSheet.getRow(1).getCell(0).getCellType()); assertEquals(order.getOrderNo(), itemSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(CellType.STRING, itemSheet.getRow(1).getCell(3).getCellType()); assertEquals("=菜名也作为文本", itemSheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals(3, itemSheet.getRow(1).getCell(5).getNumericCellValue()); assertEquals(35.25, itemSheet.getRow(1).getCell(6).getNumericCellValue());
            assertEquals(3, book.getSheet("收款退款流水").getPhysicalNumberOfRows());
        }
    }
}
