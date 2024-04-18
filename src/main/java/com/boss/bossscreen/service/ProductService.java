package com.boss.bossscreen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.bossscreen.dto.ConditionDTO;
import com.boss.bossscreen.enities.Product;
import com.boss.bossscreen.vo.PageResult;
import com.boss.bossscreen.vo.ProductDetailVO;
import com.boss.bossscreen.vo.ProductVO;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */
public interface ProductService extends IService<Product> {

    void saveOrUpdateProduct();

    PageResult<ProductVO> productListByCondition(ConditionDTO conditionDTO);


    ProductDetailVO getProductDetail(Long itemId);
//
//    void updateAccountsStatus(UpdateStatusDTO updateStatusDTO);

//    void refreshAccountToken();
}
