package com.boss.client.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.client.vo.OrderEscrowInfoVO;
import com.boss.client.vo.OrderEscrowVO;
import com.boss.client.vo.PageResult;
import com.boss.common.vo.SelectVO;
import com.boss.common.dto.ConditionDTO;
import com.boss.common.enities.Order;

import java.util.List;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */
public interface OrderService extends IService<Order> {

    void refreshOrderByTime(long startTime, long endTime);

    PageResult<OrderEscrowVO> orderListByCondition(ConditionDTO condition);

    OrderEscrowInfoVO getOrderInfo(String orderSn);

    List<SelectVO> getStatusSelect();

    void refreshOrder(List<String> sns);

    void refreshOrderByStatus(String... status);

    void refreshNewOrder();

    void initOrder(long shopId);
}