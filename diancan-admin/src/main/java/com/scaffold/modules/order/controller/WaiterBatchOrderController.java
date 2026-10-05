package com.scaffold.modules.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.scaffold.common.result.Result;
import com.scaffold.modules.order.dto.AddOrderBatchDTO;
import com.scaffold.modules.order.service.WaiterBatchOrderService;
import com.scaffold.modules.order.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class WaiterBatchOrderController {
    private final WaiterBatchOrderService service;

    @PostMapping("/{id}/add-items")
    @SaCheckPermission("order:add-item")
    public Result<OrderVO> addItems(@PathVariable Long id, @Valid @RequestBody AddOrderBatchDTO request) {
        return Result.success(service.add(id, request));
    }
}
