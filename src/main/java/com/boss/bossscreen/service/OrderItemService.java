package com.boss.bossscreen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.bossscreen.enities.OrderItem;
import com.boss.bossscreen.vo.OrderEscrowItemVO;

import java.util.List;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */
public interface OrderItemService extends IService<OrderItem> {

    List<OrderEscrowItemVO> getOrderItemVOListByOrderSn(String orderSn);
}
