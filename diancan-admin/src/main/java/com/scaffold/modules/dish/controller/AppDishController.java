package com.scaffold.modules.dish.controller;

import com.scaffold.common.result.Result;
import com.scaffold.modules.dish.service.DishService;
import com.scaffold.modules.dish.vo.DishListVO;
import com.scaffold.modules.dish.vo.DishVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 菜品控制器（小程序端）
 *
 * @author Henfon
 */
@Tag(name = "菜品（小程序端）")
@RestController
@RequestMapping("/app/dish")
@RequiredArgsConstructor
public class AppDishController {

    private final DishService dishService;

    @Operation(summary = "获取上架菜品列表（按分类分组）")
    @GetMapping("/list")
    public Result<Map<Long, List<DishListVO>>> list() {
        return Result.success(dishService.listOnSaleDishes());
    }

    @Operation(summary = "获取菜品详情")
    @GetMapping("/{id}")
    public Result<DishVO> detail(@Parameter(description = "菜品ID") @PathVariable Long id) {
        dishService.requireOrderableDish(id);
        return Result.success(dishService.getDishDetail(id));
    }

    @Operation(summary = "搜索菜品")
    @GetMapping("/search")
    public Result<List<DishVO>> search(@Parameter(description = "搜索关键词") @RequestParam String keyword) {
        return Result.success(dishService.searchDishes(keyword));
    }

    @Operation(summary = "估清/取消估清（后厨使用）")
    @PutMapping("/{id}/sold-out")
    @cn.dev33.satoken.annotation.SaCheckPermission("dish:sold-out")
    public Result<Void> markSoldOut(@Parameter(description = "菜品ID") @PathVariable Long id,
                                    @Parameter(description = "是否售罄（0否 1是）") @RequestParam Integer soldOut) {
        if(soldOut==null || (soldOut!=0 && soldOut!=1))throw new com.scaffold.common.exception.BusinessException(com.scaffold.common.result.ResultCode.PARAM_ERROR,"售卖状态无效");
        dishService.markSoldOut(id, soldOut);
        return Result.success();
    }
}
