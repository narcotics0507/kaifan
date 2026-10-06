package com.scaffold.modules.report.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.scaffold.common.result.Result;
import com.scaffold.modules.report.service.DailyReconciliationService;
import com.scaffold.modules.system.mapper.SysUserMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;
@RestController @RequiredArgsConstructor
@RequestMapping("/admin/report/revenue/reconciliation")
@SaCheckPermission("report:revenue")
public class DailyReconciliationController {
 private final DailyReconciliationService service;
 private final SysUserMapper users;
 @GetMapping public Result<Map<String,Object>> review(@RequestParam @DateTimeFormat(pattern="yyyy-MM-dd") LocalDate date){return Result.success(service.review(date));}
 @PostMapping @SaCheckPermission({"report:revenue","payment:cash"})
 public Result<Map<String,Object>> save(@Valid @RequestBody DailyReconciliationService.SaveRequest request){
  long id=StpUtil.getLoginIdAsLong();var user=users.selectById(id);String name=user==null?String.valueOf(id):user.getNickname()==null||user.getNickname().isBlank()?user.getUsername():user.getNickname();
  return Result.success(service.save(request,id,name));
 }
}
