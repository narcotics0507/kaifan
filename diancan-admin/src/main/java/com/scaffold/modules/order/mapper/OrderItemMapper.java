package com.scaffold.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scaffold.modules.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单项 Mapper
 *
 * @author Henfon
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
    @Select("SELECT * FROM order_item WHERE id = #{id}")
    OrderItem selectIncludingReturned(@Param("id") Long id);

    @Select("SELECT * FROM order_item WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    OrderItem selectByIdForUpdate(@Param("id") Long id);
}
