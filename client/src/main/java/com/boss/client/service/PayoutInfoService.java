package com.boss.client.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.client.vo.PayoutInfoVO;
import com.boss.common.enities.PayoutInfo;


/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/10
 */
public interface PayoutInfoService extends IService<PayoutInfo>  {

    void refreshPayoutInfoByTime(String startTime, String endTime);

    PayoutInfoVO getPayoutInfoBySn(String orderSn);

//    void saveOrUpdateCost(CostDTO costDTO);
//
//    void deleteCost(List<Integer> ids);
//
//    PageResult<CostVO> costListByCondition(ConditionDTO condition);
//
//    CostVO getCostById(int id);
//
//    List<SelectVO> getCostType();
}