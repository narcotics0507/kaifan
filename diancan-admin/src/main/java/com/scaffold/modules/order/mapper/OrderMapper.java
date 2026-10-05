package com.scaffold.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scaffold.modules.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单 Mapper
 *
 * @author Henfon
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    @Select("SELECT * FROM `order` WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Order selectByIdForUpdate(@Param("id") Long id);
}
