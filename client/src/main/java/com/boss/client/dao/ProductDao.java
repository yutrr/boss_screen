package com.boss.client.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boss.client.vo.ProductVO;
import com.boss.common.dto.ConditionDTO;
import com.boss.common.enities.Product;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;


/**
 * 分类
 */
@Repository
public interface ProductDao extends BaseMapper<Product> {


    Integer productCount(@Param("condition") ConditionDTO condition);


    List<ProductVO> productList(@Param("current") Long current, @Param("size") Long size, @Param("condition") ConditionDTO condition);

}