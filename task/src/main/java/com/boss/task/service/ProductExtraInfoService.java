package com.boss.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.common.enities.ProductExtraInfo;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */
public interface ProductExtraInfoService extends IService<ProductExtraInfo> {

    void saveOrUpdateProductExtraInfo();

}