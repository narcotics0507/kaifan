package com.scaffold.modules.kitchen.controller;

import com.scaffold.common.result.Result;
import com.scaffold.modules.kitchen.service.KitchenService;
import com.scaffold.modules.kitchen.vo.KitchenTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后厨任务控制器
 *
 * @author Henfon
 */
@Tag(name = "后厨任务")
@RestController
@RequestMapping("/app/kitchen")
@RequiredArgsConstructor
public class AppKitchenController {

    private final KitchenService kitchenService;
    private final com.scaffold.modules.order.service.OrderService orders;
    private final com.scaffold.modules.print.service.KitchenPaperService papers;
    private final com.scaffold.modules.order.mapper.OrderItemMapper itemMapper;

    @GetMapping("/bills")
    @cn.dev33.satoken.annotation.SaCheckPermission("kitchen:tasks")
    public Result<List<com.scaffold.modules.order.vo.OrderVO>> bills(){
        return Result.success(kitchenService.getCurrentBillIds().stream().map(orders::getOrderDetail).filter(order->order.getStatus()==0 || order.getStatus()==1).peek(order->order.setCustomerOpenid(null)).toList());
    }
    @GetMapping("/papers/{orderId}")
    @cn.dev33.satoken.annotation.SaCheckPermission("kitchen:tasks")
    public Result<List<com.scaffold.modules.print.vo.KitchenPaperVO>> papers(@PathVariable Long orderId){return Result.success(papers.list(orderId));}
    @GetMapping("/receipt/{orderId}")
    @cn.dev33.satoken.annotation.SaCheckPermission("kitchen:tasks")
    public Result<String> receipt(@PathVariable Long orderId){
        return Result.success(papers.receiptSnapshot(orderId));
    }
    @PostMapping("/item/{itemId}/waive")
    @cn.dev33.satoken.annotation.SaCheckPermission("kitchen:waive")
    public Result<com.scaffold.modules.order.vo.OrderVO> waive(@PathVariable Long itemId,@Valid @RequestBody com.scaffold.modules.order.dto.KitchenWaiveDTO dto){return Result.success(orders.kitchenWaiveItem(itemId,dto));}
    @PostMapping("/item/{itemId}/shortage-return")
    @cn.dev33.satoken.annotation.SaCheckPermission("kitchen:shortage-return")
    public Result<com.scaffold.modules.order.vo.OrderVO> shortage(@PathVariable Long itemId,@Valid @RequestBody com.scaffold.modules.order.dto.ShortageReturnDTO dto){return Result.success(orders.shortageReturn(itemId,dto));}


    @Operation(summary = "获取待制作/制作中任务列表")
    @GetMapping("/tasks")
    public Result<List<KitchenTaskVO>> getTaskList() {
        return Result.success(kitchenService.getTaskList());
    }

    @Operation(summary = "接单")
    @PutMapping("/task/{itemId}/accept")
    public Result<Void> acceptTask(@PathVariable Long itemId) {
        kitchenService.acceptTask(itemId);
        return Result.success();
    }

    /**
     * 获取自动接单开关
     *
     * @author Henfon
     * @date 2026-06-26
     * @description 返回后厨当前是否开启自动接单。
     * @return 自动接单开关状态
     */
    @Operation(summary = "获取自动接单开关")
    @GetMapping("/auto-accept")
    public Result<Boolean> getAutoAcceptEnabled() {
        return Result.success(kitchenService.isAutoAcceptEnabled());
    }

    /**
     * 更新自动接单开关
     *
     * @author Henfon
     * @date 2026-06-26
     * @description 设置后厨是否对新堂食订单自动接单。
     * @param enabled 是否开启
     * @return 空
     */
    @Operation(summary = "更新自动接单开关")
    @PutMapping("/auto-accept")
    public Result<Void> updateAutoAcceptEnabled(@RequestParam boolean enabled) {
        kitchenService.updateAutoAcceptEnabled(enabled);
        return Result.success();
    }

    @Operation(summary = "划单")
    @PutMapping("/task/{itemId}/complete")
    public Result<Void> completeTask(@PathVariable Long itemId) {
        kitchenService.completeTask(itemId);
        return Result.success();
    }
}
