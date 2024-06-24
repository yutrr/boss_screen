package com.boss.task.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boss.common.enities.Product;
import org.springframework.stereotype.Repository;


/**
 * 分类
 */
@Repository
public interface ProductDao extends BaseMapper<Product> {

}