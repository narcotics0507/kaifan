package com.scaffold.modules.report.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.SimpleColumnWidthStyleStrategy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.modules.order.entity.Order;
import com.scaffold.modules.order.entity.OrderItem;
import com.scaffold.modules.order.entity.OrderOperationLog;
import com.scaffold.modules.order.mapper.OrderMapper;
import com.scaffold.modules.order.mapper.OrderItemMapper;
import com.scaffold.modules.order.mapper.OrderOperationLogMapper;
import com.scaffold.modules.payment.entity.PaymentRecord;
import com.scaffold.modules.payment.mapper.PaymentRecordMapper;
import com.scaffold.modules.report.vo.RevenueDailyVO;
import com.scaffold.modules.report.vo.RevenueDailyVO.*;
import com.scaffold.modules.report.vo.RevenueVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only accounting projections over existing receipts, bills and audit logs. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RevenueLedgerService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final PaymentRecordMapper paymentMapper;
    private final OrderOperationLogMapper logMapper;
    private static final List<String> ADJUSTMENTS = List.of("RETURN", "SHORTAGE_RETURN", "GIFT", "KITCHEN_WAIVE", "DISCOUNT", "REPLACE", "REFUND_ORDER", "TABLEWARE");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<RevenueDailyVO> daily(LocalDate start, LocalDate end) {
        return load(start, end, false);
    }

    public RevenueDailyVO detail(LocalDate date) {
        return load(date, date, true).get(0);
    }

    public List<RevenueVO> trend(String dimension, LocalDate start, LocalDate end) {
        if (!List.of("day", "week", "month").contains(dimension)) throw invalid("请选择按日、按周或按月");
        Map<String, RevenueVO> periods = new LinkedHashMap<>();
        for (RevenueDailyVO day : daily(start, end)) {
            LocalDate date = LocalDate.parse(day.getDate());
            String key = switch (dimension) {
                case "week" -> date.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                case "month" -> day.getDate().substring(0, 7);
                default -> day.getDate();
            };
            RevenueVO period = periods.computeIfAbsent(key, k -> {
                RevenueVO vo = new RevenueVO(); vo.setDate(k); vo.setTotalRevenue(BigDecimal.ZERO); vo.setOrderCount(0); return vo;
            });
            period.setTotalRevenue(period.getTotalRevenue().add(day.getTotalRevenue()));
            period.setOrderCount(period.getOrderCount() + day.getOrderCount());
        }
        return new ArrayList<>(periods.values());
    }

    private List<RevenueDailyVO> load(LocalDate start, LocalDate end, boolean details) {
        validate(start, end);
        LocalDateTime from = start.atStartOfDay(), until = end.plusDays(1).atStartOfDay();
        List<PaymentRecord> received = paymentMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .in(PaymentRecord::getStatus, 1, 2).ge(PaymentRecord::getCreateTime, from).lt(PaymentRecord::getCreateTime, until));
        List<OrderOperationLog> dayLogs = logMapper.selectList(new LambdaQueryWrapper<OrderOperationLog>()
                .in(OrderOperationLog::getOperationType, ADJUSTMENTS).ge(OrderOperationLog::getCreateTime, from).lt(OrderOperationLog::getCreateTime, until));
        List<Order> opened = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreateTime, from).lt(Order::getCreateTime, until));
        Set<Long> ids = new LinkedHashSet<>();
        received.forEach(p -> ids.add(p.getOrderId())); dayLogs.forEach(l -> ids.add(l.getOrderId())); opened.forEach(o -> ids.add(o.getId()));
        Map<Long, Order> orders = ids.isEmpty() ? Map.of() : orderMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        // Deleted test bills and their residual records never enter business totals.
        received = received.stream().filter(p -> orders.containsKey(p.getOrderId())).toList();
        dayLogs = dayLogs.stream().filter(l -> orders.containsKey(l.getOrderId())).toList();
        List<OrderOperationLog> history = details && !orders.isEmpty() ? logMapper.selectList(new LambdaQueryWrapper<OrderOperationLog>()
                .in(OrderOperationLog::getOrderId, orders.keySet()).in(OrderOperationLog::getOperationType, ADJUSTMENTS)
                .orderByAsc(OrderOperationLog::getCreateTime, OrderOperationLog::getId)) : dayLogs;
        Set<Long> refundIds = dayLogs.stream().filter(l -> "REFUND_ORDER".equals(l.getOperationType())).map(OrderOperationLog::getOrderId).collect(Collectors.toSet());
        List<PaymentRecord> refunded = refundIds.isEmpty() ? List.of() : paymentMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .in(PaymentRecord::getOrderId, refundIds).eq(PaymentRecord::getStatus, 2));
        Map<String, RevenueDailyVO> days = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) { RevenueDailyVO day = new RevenueDailyVO(); day.setDate(d.toString()); days.put(day.getDate(), day); }
        for (PaymentRecord p : received) {
            RevenueDailyVO day = days.get(p.getCreateTime().toLocalDate().toString());
            Receipt receipt = receipt(p, orders.get(p.getOrderId()), p.getCreateTime(), "收款", null);
            day.getPayments().add(receipt); day.setReceivedAmount(day.getReceivedAmount().add(money(p.getAmount()))); channel(day, p.getPaymentMethod(), money(p.getAmount()));
        }
        // Whole-order refunds are dated by the refund audit event, not the opening date
        // or a mutable bill status. Original receipts remain visible on their original day.
        Set<Long> seenRefunds = new HashSet<>();
        for (OrderOperationLog log : dayLogs) {
            RevenueDailyVO day = days.get(log.getCreateTime().toLocalDate().toString());
            if ("REFUND_ORDER".equals(log.getOperationType()) && seenRefunds.add(log.getOrderId())) {
                for (PaymentRecord p : refunded) if (p.getOrderId().equals(log.getOrderId())) {
                    Receipt receipt = receipt(p, orders.get(p.getOrderId()), log.getCreateTime(), "退款", log);
                    day.getPayments().add(receipt); day.setRefundAmount(day.getRefundAmount().add(money(p.getAmount()))); channel(day, p.getPaymentMethod(), money(p.getAmount()).negate());
                }
            }
            Adjustment adjustment = adjustment(log, orders.get(log.getOrderId())); day.getAdjustments().add(adjustment);
            if (List.of("RETURN", "SHORTAGE_RETURN").contains(log.getOperationType())) day.setReturnedAmount(day.getReturnedAmount().add(money(adjustment.getAmount())));
            if (List.of("GIFT", "KITCHEN_WAIVE").contains(log.getOperationType())) day.setWaivedAmount(day.getWaivedAmount().add(money(adjustment.getAmount())));
        }
        for (Order order : opened) {
            RevenueDailyVO day = days.get(order.getCreateTime().toLocalDate().toString());
            day.setOpenedOrderCount(day.getOpenedOrderCount() + 1);
            day.setUnsettledAmount(day.getUnsettledAmount().add(due(order)));
        }
        Map<Long, List<OrderItem>> items = details && !orders.isEmpty() ? itemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orders.keySet()).orderByAsc(OrderItem::getAddedAt, OrderItem::getId)).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId)) : Map.of();
        for (RevenueDailyVO day : days.values()) {
            day.setTotalRevenue(day.getReceivedAmount().subtract(day.getRefundAmount()));
            day.setOrderCount((int) day.getPayments().stream().filter(p -> "收款".equals(p.getKind())).map(Receipt::getOrderId).distinct().count());
            day.getPayments().sort(Comparator.comparing(Receipt::getTime).thenComparing(Receipt::getId));
            if (details) {
                Set<String> related = day.getPayments().stream().map(Receipt::getOrderId).collect(Collectors.toCollection(LinkedHashSet::new));
                day.getAdjustments().forEach(a -> related.add(a.getOrderId()));
                opened.stream().filter(o -> day.getDate().equals(o.getCreateTime().toLocalDate().toString())).forEach(o -> related.add(o.getId().toString()));
                for (String id : related) {
                    Order order = orders.get(Long.valueOf(id));
                    Bill bill = bill(order, items.getOrDefault(order.getId(), List.of()), history, day); day.getOrders().add(bill);
                }
                day.getOrders().sort(Comparator.comparing(Bill::getCreateTime).reversed().thenComparing(Bill::getId));
            } else { day.setPayments(List.of()); day.setAdjustments(List.of()); }
        }
        return new ArrayList<>(days.values());
    }

    private Bill bill(Order order, List<OrderItem> items, List<OrderOperationLog> logs, RevenueDailyVO day) {
        Bill bill = new Bill(); bill.setId(order.getId().toString()); bill.setOrderNo(order.getOrderNo()); bill.setTableCode(order.getTableCode());
        bill.setTableSessionCode(order.getTableSessionCode()); bill.setCreateTime(order.getCreateTime()); bill.setRemark(order.getRemark());
        bill.setStatus(switch (order.getStatus() == null ? -1 : order.getStatus()) { case 0 -> "待结账"; case 1 -> "已结账"; case 2 -> "已取消"; case 3 -> "已退款"; default -> "未知"; });
        BigDecimal original = BigDecimal.ZERO;
        for (OrderItem item : items) {
            Item row = new Item(); row.setId(item.getId().toString()); row.setDishName(item.getDishName()); row.setPrice(item.getPrice()); row.setQuantity(item.getQuantity());
            row.setAmount(item.getAmount()); row.setBillingStatus(Integer.valueOf(1).equals(item.getIsGift()) ? "免单/赠送" : "计费"); row.setRemark(item.getRemark());
            row.setAddedAt(item.getAddedAt() == null ? item.getCreateTime() : item.getAddedAt()); bill.getItems().add(row);
            original = original.add(money(item.getPrice()).multiply(BigDecimal.valueOf(item.getQuantity() == null ? 0 : item.getQuantity())));
        }
        bill.setGuestCount(order.getGuestCount()); bill.setTablewareQuantity(order.getTablewareQuantity());
        bill.setTablewareUnitPrice(order.getTablewareUnitPrice()); bill.setTablewareAmount(money(order.getTablewareAmount())); bill.setTablewareOwner(order.getTablewareOwner());
        original = original.add(bill.getTablewareAmount());
        bill.setOriginalAmount(original); bill.setActualAmount(money(order.getActualAmount())); bill.setDiscountAmount(original.subtract(bill.getActualAmount()).max(BigDecimal.ZERO));
        bill.setPaidAmount(money(order.getPaidAmount())); bill.setUnsettledAmount(due(order));
        bill.setDayReceivedAmount(sum(day.getPayments(), bill.getId(), "收款")); bill.setDayRefundAmount(sum(day.getPayments(), bill.getId(), "退款"));
        bill.setDayNetAmount(bill.getDayReceivedAmount().subtract(bill.getDayRefundAmount()));
        bill.setPaymentMethods(day.getPayments().stream().filter(p -> bill.getId().equals(p.getOrderId())).map(Receipt::getPaymentMethod).distinct().collect(Collectors.joining("、")));
        logs.stream().filter(l -> order.getId().equals(l.getOrderId())).forEach(l -> bill.getAdjustments().add(adjustment(l, order)));
        return bill;
    }

    private BigDecimal sum(List<Receipt> rows, String id, String kind) {
        return rows.stream().filter(r -> id.equals(r.getOrderId()) && kind.equals(r.getKind())).map(Receipt::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private Receipt receipt(PaymentRecord p, Order order, LocalDateTime time, String kind, OrderOperationLog log) {
        Receipt row = new Receipt(); row.setId(kind + "-" + p.getId()); row.setOrderId(order.getId().toString()); row.setOrderNo(order.getOrderNo()); row.setTableCode(order.getTableCode());
        row.setPaymentNo(p.getPaymentNo()); row.setTime(time); row.setKind(kind); row.setPaymentMethod(method(p.getPaymentMethod())); row.setAmount(money(p.getAmount()));
        if (log != null) { row.setReason(log.getReason()); row.setOperatorName(log.getOperatorName()); } return row;
    }
    private Adjustment adjustment(OrderOperationLog log, Order order) {
        Adjustment row = new Adjustment(); row.setId(log.getId().toString()); row.setOrderId(order.getId().toString()); row.setOrderNo(order.getOrderNo()); row.setTableCode(order.getTableCode());
        row.setTime(log.getCreateTime()); row.setOperatorName(log.getOperatorName()); row.setReason(log.getReason());
        row.setKind(switch (log.getOperationType()) { case "SHORTAGE_RETURN" -> "缺菜退掉"; case "RETURN" -> "退菜"; case "GIFT" -> "赠送"; case "KITCHEN_WAIVE" -> "这道菜免单"; case "DISCOUNT" -> "整单折扣"; case "REPLACE" -> "换菜"; case "REFUND_ORDER" -> "整单退款"; case "TABLEWARE" -> "人数/餐具调整"; default -> log.getOperationType(); });
        try {
            JSONObject data = JSONUtil.parseObj(log.getDetail()); row.setDishName(data.getStr("dishName", data.getStr("oldDish"))); row.setQuantity(data.getInt("quantity", data.getInt("oldQuantity")));
            row.setAmount(switch (log.getOperationType()) { case "GIFT" -> data.getBigDecimal("originalAmount"); case "REFUND_ORDER" -> data.getBigDecimal("refundAmount");
                case "DISCOUNT" -> data.getBigDecimal("originalAmount") == null || data.getBigDecimal("actualAmount") == null ? null : data.getBigDecimal("originalAmount").subtract(data.getBigDecimal("actualAmount"));
                default -> data.getBigDecimal("amount"); });
            if ("TABLEWARE".equals(log.getOperationType())) row.setDescription("人数 " + data.getInt("oldGuestCount") + " → " + data.getInt("guestCount") + "；餐具 " + data.getInt("oldQuantity") + " → " + data.getInt("quantity"));
            if ("REPLACE".equals(log.getOperationType())) row.setDescription(data.getStr("oldDish", "") + " → " + data.getStr("newDish", ""));
        } catch (RuntimeException ignored) { row.setDescription("历史记录格式不完整，金额未记录"); }
        return row;
    }
    private void channel(RevenueDailyVO day, Integer method, BigDecimal amount) {
        if (method != null && (method == 0 || method == 3)) day.setWechatAmount(day.getWechatAmount().add(amount));
        else if (method != null && (method == 1 || method == 4)) day.setAlipayAmount(day.getAlipayAmount().add(amount));
        else if (Integer.valueOf(2).equals(method)) day.setCashAmount(day.getCashAmount().add(amount));
        else day.setOtherAmount(day.getOtherAmount().add(amount));
    }
    private String method(Integer method) { return switch (method == null ? -1 : method) { case 0 -> "微信在线支付"; case 1 -> "支付宝在线支付"; case 2 -> "现金"; case 3 -> "微信收款码"; case 4 -> "支付宝收款码"; default -> "其他/未记录"; }; }
    private BigDecimal due(Order o) { return Integer.valueOf(0).equals(o.getStatus()) ? money(o.getActualAmount()).subtract(money(o.getPaidAmount())).max(BigDecimal.ZERO) : BigDecimal.ZERO; }
    private BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
    private void validate(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 365) throw invalid("请选择开始不晚于结束、最多366天的日期范围");
    }

    public void export(LocalDate start, LocalDate end, HttpServletResponse response) {
        List<RevenueDailyVO> days = load(start, end, true);
        List<List<Object>> daily = new ArrayList<>(), bills = new ArrayList<>(), items = new ArrayList<>(), payments = new ArrayList<>(), adjustments = new ArrayList<>(), tableware = new ArrayList<>();
        Set<String> exportedBills = new HashSet<>();
        for (RevenueDailyVO day : days) {
            daily.add(row(day.getDate(), day.getReceivedAmount(), day.getRefundAmount(), day.getTotalRevenue(), day.getOrderCount(), day.getWechatAmount(), day.getAlipayAmount(), day.getCashAmount(), day.getOtherAmount(), day.getOpenedOrderCount(), day.getUnsettledAmount(), day.getReturnedAmount(), day.getWaivedAmount()));
            for (Bill bill : day.getOrders()) {
                bills.add(row(day.getDate(), bill.getOrderNo(), bill.getTableCode(), bill.getTableSessionCode(), bill.getCreateTime(), bill.getStatus(), bill.getOriginalAmount(), bill.getDiscountAmount(), bill.getActualAmount(), bill.getPaidAmount(), bill.getUnsettledAmount(), bill.getDayReceivedAmount(), bill.getDayRefundAmount(), bill.getDayNetAmount(), bill.getPaymentMethods(), bill.getRemark(), bill.getGuestCount(), bill.getTablewareQuantity(), bill.getTablewareUnitPrice(), bill.getTablewareAmount()));
                if (exportedBills.add(bill.getId())) {
                    if (bill.getGuestCount() != null && bill.getGuestCount() > 0) tableware.add(row(bill.getOrderNo(), bill.getTableCode(), bill.getCreateTime(), bill.getGuestCount(), bill.getTablewareQuantity(), bill.getTablewareUnitPrice(), bill.getTablewareAmount(), bill.getStatus()));
                    for (Item item : bill.getItems()) items.add(row(bill.getOrderNo(), bill.getTableCode(), item.getAddedAt(), item.getDishName(), item.getPrice(), item.getQuantity(), item.getAmount(), item.getBillingStatus(), item.getRemark()));
                }
            }
            for (Receipt receipt : day.getPayments()) payments.add(row(receipt.getTime(), receipt.getOrderNo(), receipt.getTableCode(), receipt.getPaymentNo(), receipt.getKind(), receipt.getPaymentMethod(), receipt.getAmount(), receipt.getOperatorName(), receipt.getReason()));
            for (Adjustment a : day.getAdjustments()) adjustments.add(row(a.getTime(), a.getOrderNo(), a.getTableCode(), a.getKind(), a.getDishName(), a.getQuantity(), a.getAmount(), a.getOperatorName(), a.getReason(), a.getDescription()));
        }
        List<Object> total = new ArrayList<>(); total.add("合计");
        for (int column = 1; column < 13; column++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (List<Object> values : daily) sum = sum.add(new BigDecimal(values.get(column).toString()));
            total.add(sum);
        }
        daily.add(total);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); response.setCharacterEncoding("utf-8");
        String name = "营业明细_" + start + "_" + end + ".xlsx";
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).autoCloseStream(false).registerWriteHandler(new SimpleColumnWidthStyleStrategy(22)).registerWriteHandler(new RevenueWorkbookStyle()).build()) {
            sheet(writer, 0, "每日汇总", List.of("日期", "收款金额", "退款金额", "净营业额", "收款订单数", "微信净收款", "支付宝净收款", "现金净收款", "其他净收款", "新开订单数", "当日开单当前待收", "退菜记录金额", "免单赠送记录金额"), daily);
            sheet(writer, 1, "订单明细", List.of("统计日期", "订单号", "桌号", "桌次", "开单时间", "当前状态", "保留菜品及餐具原价合计", "优惠免单合计", "账单应收", "累计已收", "当前待收", "当日收款", "当日退款", "当日净收款", "当日收款方式", "订单备注", "用餐人数", "餐具套数", "餐具单价", "餐具费"), bills);
            sheet(writer, 2, "菜品明细", List.of("订单号", "桌号", "点菜时间", "菜名", "单价", "保留数量", "优惠前计费小计", "计费状态", "口味备注"), items);
            sheet(writer, 3, "收款退款流水", List.of("发生时间", "订单号", "桌号", "收款流水号", "类型", "收款方式", "金额", "操作人", "原因"), payments);
            sheet(writer, 4, "退菜免单记录", List.of("操作时间", "订单号", "桌号", "操作", "菜名", "数量", "记录金额", "操作人", "原因", "说明"), adjustments);
            sheet(writer, 5, "餐具明细", List.of("订单号", "桌号", "开单时间", "用餐人数", "餐具套数", "餐具单价", "餐具费", "当前状态"), tableware);
            sheet(writer, 6, "统计说明", List.of("项目", "说明"), List.of(
                    row("日期范围", start + " 至 " + end + "，北京时间自然日"),
                    row("净营业额", "实际收款减当日整单退款；现金按扣除找零后的入账金额，未收款不算营业额。"),
                    row("收款日期", "当前门店现金、微信/支付宝收款码按实际确认产生的流水时间；跨天开单按收款日入账。"),
                    row("订单数", "当日有收款流水的去重订单数；跨天多次收款的同一订单在各收款日分别计数。"),
                    row("退菜与免单", "是结账前的账单调整，已包含在最终应收中，不再从实收重复扣减。未记录金额留空，不推算。"),
                    row("历史与当前", "原价、应收、已收、待收、状态和保留菜品为查询时的账单状态；退款不抹掉原日收款。"),
                    row("菜品明细", "日期范围内开单、收款或发生调整的关联订单，每单导出一次；退掉或换掉的菜另见操作记录。菜品小计未分摊整单折扣。"),
                    row("新开与待收", "新开订单含取消/退款单；待收是这些新开订单当前仍未收齐的金额，不是当时的历史余额。"),
                    row("收款方式", "各方式展示收款减退款后的净额，合计等于净营业额。"),
                    row("餐具费", "首次确认人数后按1元/套计费，同桌加菜不重复收费，前台调整留痕；历史账单不自动补收，餐具费不参与菜品折扣与免单。"),
                    row("日均营业额", "净营业额除以所选自然日天数，包含零营业日期。")));
        } catch (IOException e) { throw new BusinessException("导出营业明细失败，请重试"); }
    }
    private void sheet(ExcelWriter writer, int index, String name, List<String> headers, List<List<Object>> rows) {
        WriteSheet sheet = EasyExcel.writerSheet(index, name).head(headers.stream().map(List::of).toList()).build(); writer.write(rows, sheet);
    }
    private List<Object> row(Object... values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof LocalDateTime time) value = TIME.format(time);
            // Text is always stored as text, including order numbers and formula-looking names.
            result.add(value);
        }
        return result;
    }
}
