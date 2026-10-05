package com.scaffold.integration;
import com.scaffold.DiancanAdminApplication;
import com.scaffold.modules.h5.H5Service;
import com.scaffold.modules.table.entity.DiningTable;
import com.scaffold.modules.table.service.DiningTableService;
import com.scaffold.modules.dish.entity.Dish;
import com.scaffold.modules.dish.entity.DishCategory;
import com.scaffold.modules.dish.service.DishService;
import com.scaffold.modules.dish.service.DishCategoryService;
import com.scaffold.framework.websocket.WsService;
import com.scaffold.modules.system.service.SysUserService;
import com.scaffold.modules.system.entity.SysUser;
import com.scaffold.modules.order.service.OrderService;
import com.scaffold.modules.payment.dto.CashPayDTO;
import com.scaffold.modules.payment.service.PaymentService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import static org.mockito.Mockito.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(classes=DiancanAdminApplication.class,properties={"restaurant.h5.enabled=true","restaurant.h5.signing-key=h5-test-signing-key-64-characters-only-in-isolated-test","restaurant.h5.public-origin=http://localhost"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class H5FlowIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired H5Service h5;
 @SpyBean DiningTableService tables;@Autowired DishService dishes;@Autowired DishCategoryService categories;
 @Autowired SysUserService users;@Autowired OrderService orders;@Autowired PaymentService payments;
 @MockBean WsService ws;
 record Guest(String token,Cookie cookie) {}
 JsonNode call(String method,String path,String token,Object body) throws Exception {
  var builder=switch(method){case "POST"->post(path);case "PUT"->put(path);case "DELETE"->delete(path);default->get(path);};
  if(token!=null)builder.header("Authorization",token);
  if(body!=null)builder.contentType("application/json").content(json.writeValueAsString(body));
  return json.readTree(mvc.perform(builder).andReturn().getResponse().getContentAsString());
 }
 JsonNode ok(String method,String path,String token,Object body) throws Exception {JsonNode r=call(method,path,token,body);assertEquals(200,r.path("code").asInt(),r.toString());return r.path("data");}
 Guest guest() throws Exception {
  var res=mvc.perform(post("/app/h5/session")).andReturn().getResponse();JsonNode r=json.readTree(res.getContentAsString());assertEquals(200,r.path("code").asInt());
  String cookie=res.getHeaders("Set-Cookie").stream().filter(c->c.startsWith("kaifan_guest=")).findFirst().orElseThrow();assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));
  return new Guest(r.path("data").path("token").asText(),new Cookie("kaifan_guest",cookie.split(";",2)[0].split("=",2)[1]));
 }
 DiningTable table(){DiningTable t=new DiningTable();t.setCode("H5-"+UUID.randomUUID().toString().substring(0,8));t.setName("H5测试桌");t.setCapacity(4);t.setStatus(0);tables.save(t);return t;}
 Dish dish(BigDecimal price){DishCategory c=new DishCategory();c.setName("H5测试分类"+UUID.randomUUID());c.setStatus(1);c.setSort(1);categories.save(c);Dish d=new Dish();d.setName("H5测试菜"+UUID.randomUUID());d.setCategoryId(c.getId());d.setPrice(price);d.setStock(-1);d.setSoldOut(0);d.setStatus(1);dishes.save(d);return d;}
 JsonNode join(Guest g,DiningTable t)throws Exception{return ok("POST","/app/h5/join",g.token(),Map.of("tableCode",t.getCode(),"key",h5.signature(t)));}
 void add(Guest g,DiningTable t,Dish d,int qty)throws Exception{ok("POST","/app/cart/item?tableId="+t.getId(),g.token(),Map.of("dishId",d.getId(),"quantity",qty,"remark","少辣"));}
 Map<String,Object> body(DiningTable t,JsonNode context,String id){return Map.of("tableId",t.getId(),"sessionCode",context.path("currentSessionCode").asText(),"requestId",id,"remark","H5 test");}
 @Test void visitorsHaveNoRolesAndCookieResumesSameIdentity()throws Exception{
  Guest g=guest();JsonNode info=ok("GET","/auth/info",g.token(),null);assertTrue(info.path("roles").isEmpty());assertTrue(info.path("permissions").isEmpty());assertTrue(info.path("phone").isNull());
  var res=mvc.perform(post("/app/h5/session").cookie(g.cookie())).andReturn().getResponse();assertEquals(g.token(),json.readTree(res.getContentAsString()).path("data").path("token").asText());
  assertEquals(403,call("GET","/admin/table/list",g.token(),null).path("code").asInt());
  assertEquals(403,call("GET","/app/kitchen/tasks",g.token(),null).path("code").asInt());
  assertEquals(403,call("GET","/app/kitchen/bills",g.token(),null).path("code").asInt());
  assertEquals(403,call("PUT","/app/dish/1/sold-out?soldOut=1",g.token(),null).path("code").asInt());
  assertEquals(403,call("POST","/app/kitchen/item/1/shortage-return",g.token(),Map.of("quantity",1,"requestId","customer-try")).path("code").asInt());
  var denied=mvc.perform(post("/app/h5/session").header("Host","localhost").header("Origin","https://other.example")).andReturn().getResponse();assertEquals(403,json.readTree(denied.getContentAsString()).path("code").asInt());
 }
 @Test void invalidQrCannotBindAndGuestCannotBypassUsingTableId()throws Exception{
  Guest g=guest();DiningTable t=table();
  assertEquals(403,call("POST","/app/h5/join",g.token(),Map.of("tableCode",t.getCode(),"key","a".repeat(64))).path("code").asInt());
  assertEquals(0,tables.getById(t.getId()).getStatus());
  assertEquals(403,call("PUT","/app/table/"+t.getId()+"/bind",g.token(),null).path("code").asInt());
  assertEquals(403,call("POST","/app/order",g.token(),Map.of("tableId",t.getId(),"paymentMode",1)).path("code").asInt());
  assertEquals(403,call("GET","/admin/h5/table-links",g.token(),null).path("code").asInt());
 }
 @Test void twoVisitorsMergeOneBillRetryAndClearNewVisit()throws Exception{
  DiningTable t=table();Dish d= dish(new BigDecimal("22"));Guest a=guest(),b=guest();JsonNode visit=join(a,t);join(b,t);
  add(a,t,d,1);String req=UUID.randomUUID().toString();JsonNode first=ok("POST","/app/h5/submit",a.token(),body(t,visit,req));
  JsonNode repeated=ok("POST","/app/h5/submit",a.token(),body(t,visit,req));assertEquals(first.path("id"),repeated.path("id"));
  add(b,t,d,2);JsonNode second=ok("POST","/app/h5/submit",b.token(),body(t,visit,UUID.randomUUID().toString()));assertEquals(first.path("id"),second.path("id"));assertEquals(66,second.path("actualAmount").asInt());
  JsonNode list=ok("GET","/app/order/table/"+t.getId(),b.token(),null);assertEquals(1,list.size());assertTrue(list.get(0).path("customerOpenid").isNull());
  CashPayDTO pay=new CashPayDTO();pay.setOrderId(Long.valueOf(first.path("id").asText()));pay.setReceivedAmount(new BigDecimal("66"));pay.setPaymentMethod(3);payments.cashPay(pay);
  assertFalse(ok("GET","/app/h5/context",a.token(),null).path("active").asBoolean());
  assertEquals(403,call("POST","/app/h5/submit",b.token(),body(t,visit,UUID.randomUUID().toString())).path("code").asInt());
  JsonNode next=join(a,t);assertNotEquals(visit.path("currentSessionCode"),next.path("currentSessionCode"));assertEquals(0,ok("GET","/app/cart?tableId="+t.getId(),a.token(),null).path("totalCount").asInt());assertTrue(ok("GET","/app/order/table/"+t.getId(),a.token(),null).isEmpty());
 }
 @Test void simultaneousFirstOrdersMergeWithoutLostMoney()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("16"));Guest a=guest(),b=guest();JsonNode visit=join(a,t);join(b,t);add(a,t,d,2);add(b,t,d,3);
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{Future<JsonNode> x=pool.submit(()->{start.await();return ok("POST","/app/h5/submit",a.token(),body(t,visit,UUID.randomUUID().toString()));});Future<JsonNode> y=pool.submit(()->{start.await();return ok("POST","/app/h5/submit",b.token(),body(t,visit,UUID.randomUUID().toString()));});start.countDown();assertEquals(x.get(20,TimeUnit.SECONDS).path("id"),y.get(20,TimeUnit.SECONDS).path("id"));
   JsonNode all=ok("GET","/app/order/table/"+t.getId(),a.token(),null);assertEquals(1,all.size());assertEquals(80,all.get(0).path("actualAmount").asInt());
  }finally{pool.shutdownNow();}
 }
 @Test void unavailableItemRejectsWholeSubmissionAndRetainsCart()throws Exception{
  DiningTable t=table();Dish a=dish(new BigDecimal("20")),b=dish(new BigDecimal("30"));Guest g=guest();JsonNode visit=join(g,t);add(g,t,a,1);add(g,t,b,1);b.setSoldOut(1);dishes.updateById(b);
  assertNotEquals(200,call("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString())).path("code").asInt());assertTrue(ok("GET","/app/order/table/"+t.getId(),g.token(),null).isEmpty());assertEquals(2,ok("GET","/app/cart?tableId="+t.getId(),g.token(),null).path("totalCount").asInt());
 }
 @Test void partialAdditionFailureRollsBackWholeCartAndKeepsBill()throws Exception{
  DiningTable t=table();Dish a=dish(new BigDecimal("20")),b=dish(new BigDecimal("30"));Guest g=guest();JsonNode visit=join(g,t);
  add(g,t,a,1);ok("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString()));
  add(g,t,a,1);add(g,t,b,2);b.setStock(1);dishes.updateById(b);
  assertNotEquals(200,call("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString())).path("code").asInt());
  JsonNode bill=ok("GET","/app/order/table/"+t.getId(),g.token(),null);assertEquals(20,bill.get(0).path("actualAmount").asInt());assertEquals(1,bill.get(0).path("items").size());
  assertEquals(3,ok("GET","/app/cart?tableId="+t.getId(),g.token(),null).path("totalCount").asInt());assertEquals(1,dishes.getById(b.getId()).getStock());
 }
 @Test void parallelSameRequestCommitsOnlyOnce()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("16"));Guest g=guest();JsonNode visit=join(g,t);add(g,t,d,2);String id=UUID.randomUUID().toString();
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{Callable<JsonNode> work=()->{start.await();return ok("POST","/app/h5/submit",g.token(),body(t,visit,id));};Future<JsonNode> a=pool.submit(work),b=pool.submit(work);start.countDown();assertEquals(a.get(20,TimeUnit.SECONDS).path("id"),b.get(20,TimeUnit.SECONDS).path("id"));JsonNode bill=ok("GET","/app/order/table/"+t.getId(),g.token(),null);assertEquals(1,bill.size());assertEquals(32,bill.get(0).path("actualAmount").asInt());assertEquals(2,bill.get(0).path("items").get(0).path("quantity").asInt());}
  finally{pool.shutdownNow();}
 }

 @Test void receiptRacingH5CartNeverSettlesAddedFoodAtOldAmount()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("22"));Guest g=guest();JsonNode visit=join(g,t);add(g,t,d,1);JsonNode first=ok("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString()));add(g,t,d,1);
  CashPayDTO pay=new CashPayDTO();pay.setOrderId(Long.valueOf(first.path("id").asText()));pay.setReceivedAmount(new BigDecimal("22"));pay.setPaymentMethod(3);
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{
   Future<Boolean> receipt=pool.submit(()->{start.await();try{payments.cashPay(pay);return true;}catch(Exception e){return false;}});
   Future<JsonNode> submit=pool.submit(()->{start.await();return call("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString()));});
   start.countDown();boolean paid=receipt.get(20,TimeUnit.SECONDS);JsonNode result=submit.get(20,TimeUnit.SECONDS);
   var bill=orders.getById(pay.getOrderId());
   if(paid){assertEquals(new BigDecimal("22.00"),bill.getActualAmount());assertEquals(1,bill.getStatus());assertNotEquals(200,result.path("code").asInt());}
   else{assertEquals(0,bill.getStatus());assertTrue(bill.getActualAmount().compareTo(new BigDecimal("22"))>=0);}
  }finally{pool.shutdownNow();}
 }

 @Test void browsingAndCartChangesKeepTableUnopenedAndResumeVisit()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("22"));Guest a=guest(),b=guest();
  JsonNode visit=join(a,t),peer=join(b,t);assertEquals(visit.path("currentSessionCode"),peer.path("currentSessionCode"));
  assertEquals(0,tables.getById(t.getId()).getStatus());assertTrue(ok("GET","/app/h5/context",a.token(),null).path("active").asBoolean());
  add(a,t,d,2);assertEquals(0,tables.getById(t.getId()).getStatus());
  assertEquals(2,ok("GET","/app/cart?tableId="+t.getId(),a.token(),null).path("totalCount").asInt());
  ok("DELETE","/app/cart?tableId="+t.getId(),a.token(),null);assertEquals(0,tables.getById(t.getId()).getStatus());
  assertTrue(ok("GET","/app/order/table/"+t.getId(),a.token(),null).isEmpty());
  JsonNode resumed=join(a,t);assertEquals(visit.path("currentSessionCode"),resumed.path("currentSessionCode"));
  add(a,t,d,1);ok("POST","/app/h5/submit",a.token(),body(t,visit,UUID.randomUUID().toString()));
  assertEquals(1,tables.getById(t.getId()).getStatus());assertEquals(visit.path("currentSessionCode").asText(),tables.getById(t.getId()).getCurrentSessionCode());
 }
 @Test void orderOpeningFailureRollsBackBillStockAndKeepsDraft()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("22"));d.setStock(4);dishes.updateById(d);Guest g=guest();JsonNode visit=join(g,t);add(g,t,d,2);
  doThrow(new IllegalStateException("injected opening failure")).when(tables).openTable(t.getId());
  assertNotEquals(200,call("POST","/app/h5/submit",g.token(),body(t,visit,UUID.randomUUID().toString())).path("code").asInt());
  assertEquals(0,tables.getById(t.getId()).getStatus());assertTrue(ok("GET","/app/order/table/"+t.getId(),g.token(),null).isEmpty());
  assertEquals(4,dishes.getById(d.getId()).getStock());assertEquals(2,ok("GET","/app/cart?tableId="+t.getId(),g.token(),null).path("totalCount").asInt());
 }
 @Test void lastVisitorChangingTableCannotClearSubmittedBill()throws Exception{
  DiningTable original=table(),next=table();Dish d=dish(new BigDecimal("22"));Guest g=guest();JsonNode visit=join(g,original);add(g,original,d,1);
  JsonNode bill=ok("POST","/app/h5/submit",g.token(),body(original,visit,UUID.randomUUID().toString()));join(g,next);
  assertEquals(1,tables.getById(original.getId()).getStatus());assertEquals(visit.path("currentSessionCode").asText(),tables.getById(original.getId()).getCurrentSessionCode());
  assertEquals(0,tables.getById(next.getId()).getStatus());assertEquals(0,orders.getById(Long.valueOf(bill.path("id").asText())).getStatus());
  CashPayDTO pay=new CashPayDTO();pay.setOrderId(Long.valueOf(bill.path("id").asText()));pay.setReceivedAmount(new BigDecimal("22"));pay.setPaymentMethod(3);payments.cashPay(pay);
  assertEquals(0,tables.getById(original.getId()).getStatus());
 }
 @Test void settlementInvalidatesAnotherVisitorsUnsubmittedDraft()throws Exception{
  DiningTable t=table();Dish d=dish(new BigDecimal("22"));Guest a=guest(),b=guest();JsonNode visit=join(a,t);join(b,t);add(a,t,d,1);add(b,t,d,2);
  JsonNode bill=ok("POST","/app/h5/submit",a.token(),body(t,visit,UUID.randomUUID().toString()));
  CashPayDTO pay=new CashPayDTO();pay.setOrderId(Long.valueOf(bill.path("id").asText()));pay.setReceivedAmount(new BigDecimal("22"));pay.setPaymentMethod(3);payments.cashPay(pay);
  assertFalse(ok("GET","/app/h5/context",b.token(),null).path("active").asBoolean());
  assertEquals(403,call("POST","/app/h5/submit",b.token(),body(t,visit,UUID.randomUUID().toString())).path("code").asInt());
  JsonNode next=join(b,t);assertEquals(0,tables.getById(t.getId()).getStatus());assertNotEquals(visit.path("currentSessionCode"),next.path("currentSessionCode"));
  assertEquals(0,ok("GET","/app/cart?tableId="+t.getId(),b.token(),null).path("totalCount").asInt());
 }
 @Test void merchantOpenedTableRemainsOpenWhenH5VisitorBrowses()throws Exception{
  DiningTable t=table();tables.openTable(t.getId());Guest g=guest();join(g,t);assertEquals(1,tables.getById(t.getId()).getStatus());
 }

 @Test void guestCannotWaiveKitchenFood() throws Exception {Guest g=guest();assertEquals(403,call("POST","/app/kitchen/item/0/waive",g.token(),Map.of("requestId","guest-waive","reason","做错菜")).path("code").asInt());}

}
