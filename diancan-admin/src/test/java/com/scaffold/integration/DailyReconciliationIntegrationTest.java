package com.scaffold.integration;
import com.scaffold.DiancanAdminApplication;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.report.service.DailyReconciliationService;
import com.scaffold.modules.order.entity.Order;
import com.scaffold.modules.order.mapper.OrderMapper;
import com.scaffold.modules.payment.entity.PaymentRecord;
import com.scaffold.modules.payment.mapper.PaymentRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(classes=DiancanAdminApplication.class) @ActiveProfiles("test") @Transactional
class DailyReconciliationIntegrationTest {
 @MockBean WsService wsService;
 @Autowired DailyReconciliationService service;
 @Autowired OrderMapper orders;
 @Autowired PaymentRecordMapper payments;
 private final LocalDate day=LocalDate.of(2000,1,6);
 private DailyReconciliationService.SaveRequest request(Map<String,Object> review){
  var r=new DailyReconciliationService.SaveRequest();r.setDate(day);r.setRevision((Integer)review.get("revision"));r.setToken((String)review.get("token"));r.setWechat(BigDecimal.ZERO);r.setAlipay(BigDecimal.ZERO);r.setCash(BigDecimal.ZERO);r.setOther(BigDecimal.ZERO);return r;
 }
 private void receipt(String value,int method){
  var o=new Order();o.setOrderNo("CHECK"+UUID.randomUUID().toString().substring(0,20));o.setTableCode("CHECK");o.setOriginalAmount(new BigDecimal(value));o.setActualAmount(new BigDecimal(value));o.setPaidAmount(new BigDecimal(value));o.setStatus(1);o.setOrderType(0);o.setPaymentMode(1);o.setCreateTime(day.atTime(10,0));o.setDeleted(0);orders.insert(o);
  var p=new PaymentRecord();p.setOrderId(o.getId());p.setPaymentNo("CHECK"+UUID.randomUUID());p.setAmount(new BigDecimal(value));p.setPaymentMethod(method);p.setStatus(1);p.setCreateTime(day.atTime(12,0));p.setDeleted(0);payments.insert(p);
 }
 @Test void zeroDayNeedsExplicitAmountsAndPreservesHistory(){
  var before=service.review(day);var r=request(before);var saved=service.save(r,1,"员工一");assertEquals(1,saved.get("revision"));assertEquals(false,saved.get("stale"));
  var r2=request(saved);r2.setReason("再次核对");var again=service.save(r2,2,"员工二");var history=(List<?>)again.get("history");assertEquals(2,history.size());assertEquals(2,again.get("revision"));assertThrows(BusinessException.class,()->service.save(r,1,"重复点击"));
 }
 @Test void changingReceiptsMakesSavedCheckStaleAndRejectsOldDraft(){
  var r=request(service.review(day));service.save(r,1,"员工");receipt("12.34",3);
  assertEquals(true,service.review(day).get("stale"));assertThrows(BusinessException.class,()->service.save(r,1,"员工"));
  var fresh=request(service.review(day));fresh.setWechat(new BigDecimal("12.34"));assertEquals(false,service.save(fresh,1,"员工").get("stale"));
 }
 @Test void offsettingChannelErrorsStillRequireReasonAndDoNotChangeMoney(){
  receipt("10",3);receipt("20",4);var r=request(service.review(day));r.setWechat(new BigDecimal("20"));r.setAlipay(new BigDecimal("10"));
  assertThrows(BusinessException.class,()->service.save(r,1,"员工"));r.setReason("渠道误登记，待处理");long before=payments.selectCount(null);var checked=service.save(r,1,"员工");assertEquals(before,payments.selectCount(null));var system=(Map<?,?>)checked.get("system");assertEquals(0,new BigDecimal("10").compareTo((BigDecimal)system.get("wechat")));
 }
 @Test void negativeActualNetCanBeRecordedButBlankAndOverprecisionCannot(){
  var r=request(service.review(day));r.setCash(null);assertThrows(BusinessException.class,()->service.save(r,1,"员工"));r.setCash(new BigDecimal("-2"));r.setReason("核对实际现金退款");service.save(r,1,"员工");
  var next=request(service.review(day));next.setCash(new BigDecimal("1.001"));assertThrows(BusinessException.class,()->service.save(next,1,"员工"));
 }
 @Test void futureDatesAreRejected(){assertThrows(BusinessException.class,()->service.review(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)));}
}
