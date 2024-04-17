package com.boss.bossscreen.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/17
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductVO {

    private Integer id;

    /**
     * 系统 id
     */
    private Long itemId;

    /**
     * 类目 id
     */
    private Long categoryId;

    /**
     * 名称
     */
    private String itemName;

    /**
     * sku
     */
    private String itemSku;

    /**
     * 主图 url
     */
    private String mainImgUrl;

    /**
     * 等级
     */
    private String grade;

    /**
     * 所属店铺 id
     */
    private long shopId;

    /**
     * 状态
     */
    private String status;

    /**
     * 创建时间
     */
    private Long createTime;
}
