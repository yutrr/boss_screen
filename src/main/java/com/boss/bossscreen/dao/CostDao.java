package com.boss.bossscreen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boss.bossscreen.dto.ConditionDTO;
import com.boss.bossscreen.enities.Cost;
import com.boss.bossscreen.vo.CostVO;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;


/**
 * 分类
 */
@Repository
public interface CostDao extends BaseMapper<Cost> {


    Integer costCount(@Param("condition") ConditionDTO condition);


    List<CostVO> costList(@Param("current") Long current, @Param("size") Long size, @Param("condition") ConditionDTO condition);

}