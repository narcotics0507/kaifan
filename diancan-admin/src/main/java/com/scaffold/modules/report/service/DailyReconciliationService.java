package com.scaffold.modules.report.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.common.result.ResultCode;
import com.scaffold.modules.order.mapper.OrderMapper;
import com.scaffold.modules.order.entity.Order;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scaffold.modules.report.vo.RevenueDailyVO;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Append-only staff checks, independent from operational receipts and settlement. */
@Service @RequiredArgsConstructor
public class DailyReconciliationService {
 private final RevenueLedgerService ledger;
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final OrderMapper orders;
 public static final List<String> CHANNELS=List.of("wechat","alipay","cash","other");
 @Data public static class SaveRequest {
  @NotNull private LocalDate date;
  @NotNull @Min(0) private Integer revision;
  @NotBlank @Size(max=64) private String token;
  @NotNull @Digits(integer=10,fraction=2) private BigDecimal wechat;
  @NotNull @Digits(integer=10,fraction=2) private BigDecimal alipay;
  @NotNull @Digits(integer=10,fraction=2) private BigDecimal cash;
  @NotNull @Digits(integer=10,fraction=2) private BigDecimal other;
  @Size(max=500) private String reason;
 }
 private BusinessException invalid(String text){return new BusinessException(ResultCode.PARAM_ERROR,text);}
 private void date(LocalDate value){if(value==null||value.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw invalid("不能核对未来日期");}
 private Map<String,Object> snapshot(LocalDate date){
  RevenueDailyVO d=ledger.detail(date);Map<String,Object> s=new LinkedHashMap<>();
  s.put("date",date.toString());s.put("wechat",d.getWechatAmount());s.put("alipay",d.getAlipayAmount());s.put("cash",d.getCashAmount());s.put("other",d.getOtherAmount());
  s.put("received",d.getReceivedAmount());s.put("refund",d.getRefundAmount());s.put("net",d.getTotalRevenue());s.put("orderCount",d.getOrderCount());
  s.put("receipts",d.getPayments().stream().map(p->List.of(p.getId(),p.getKind(),String.valueOf(p.getTime()),p.getPaymentMethod(),p.getAmount().toPlainString())).sorted(Comparator.comparing(Object::toString)).toList());
  return s;
 }
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> decode(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 private BigDecimal money(Object value){return new BigDecimal(String.valueOf(value)).setScale(2);}
 private List<Map<String,Object>> history(LocalDate date){
  return jdbc.query("SELECT * FROM daily_reconciliation WHERE business_date=? ORDER BY revision DESC LIMIT 100",(r,n)->{
   Map<String,Object> row=new LinkedHashMap<>();row.put("revision",r.getInt("revision"));row.put("system",decode(r.getString("snapshot_json")));row.put("actual",decode(r.getString("actual_json")));row.put("token",r.getString("snapshot_token"));row.put("reason",r.getString("reason"));row.put("operator",r.getString("operator_name"));row.put("savedAt",r.getTimestamp("created_at").toLocalDateTime().toString());return row;
  },date);
 }
 @Transactional(readOnly=true) public Map<String,Object> review(LocalDate date){
  date(date);Map<String,Object> s=snapshot(date);var rows=history(date);Map<String,Object> result=new LinkedHashMap<>();
  result.put("date",date.toString());result.put("system",s);result.put("token",DigestUtil.sha256Hex(encode(s)));result.put("revision",rows.isEmpty()?0:rows.get(0).get("revision"));result.put("history",rows);
  result.put("stale",!rows.isEmpty()&&!Objects.equals(result.get("token"),rows.get(0).get("token")));
  result.put("unsettled",orders.selectList(new LambdaQueryWrapper<Order>().eq(Order::getStatus,0)).stream().filter(o->o.getActualAmount().subtract(o.getPaidAmount()==null?BigDecimal.ZERO:o.getPaidAmount()).signum()>0).map(o->Map.of("orderNo",o.getOrderNo(),"tableCode",o.getTableCode(),"amount",o.getActualAmount().subtract(o.getPaidAmount()==null?BigDecimal.ZERO:o.getPaidAmount()))).toList());
  return result;
 }
 @Transactional public Map<String,Object> save(SaveRequest request,long operatorId,String operator){
  date(request.date);var s=snapshot(request.date);String token=DigestUtil.sha256Hex(encode(s));
  if(!token.equals(request.token))throw invalid("当天流水已变化，请重新加载后核对");
  var rows=history(request.date);int revision=rows.isEmpty()?0:(Integer)rows.get(0).get("revision");
  if(request.revision==null||request.revision!=revision)throw invalid("另一位员工已保存，请重新加载后核对");
  Map<String,BigDecimal> actual=new LinkedHashMap<>();actual.put("wechat",request.wechat);actual.put("alipay",request.alipay);actual.put("cash",request.cash);actual.put("other",request.other);
  for(var amount:actual.values())if(amount==null||amount.scale()>2||amount.abs().compareTo(new BigDecimal("9999999999.99"))>0)throw invalid("实际净收款须填写有效金额，最多两位小数");
  String reason=request.reason==null?"":request.reason.trim();
  boolean difference=CHANNELS.stream().anyMatch(k->actual.get(k).compareTo(money(s.get(k)))!=0);
  if(reason.length()>500||difference&&reason.isEmpty())throw invalid("存在差额，请填写差额原因（最多500字）");
  try{jdbc.update("INSERT INTO daily_reconciliation(business_date,revision,snapshot_json,snapshot_token,actual_json,reason,operator_id,operator_name,created_at) VALUES(?,?,?,?,?,?,?,?,?)",request.date,revision+1,encode(s),token,encode(actual),reason,operatorId,operator,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));}
  catch(DuplicateKeyException e){throw invalid("另一位员工已保存，请重新加载后核对");}
  return review(request.date);
 }
}
