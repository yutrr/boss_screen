package com.boss.bossscreen.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/18
 */

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderEscrowVO {
    private Integer id;

    /**
     * 创建时间
     */
    private Long createTime;

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 运单号
     */
    private String packageNumber;

    /**
     * 状态
     */
    private String status;

    /**
     * 所属店铺
     */
    private Long shopId;

    /**
     * T恤数量
     */
    private int tShirtCount = 0;

    /**
     * 双面数量
     */
    private int doubleCount = 0;

    /**
     * 短款T恤数量
     */
    private int shortCount = 0;

    /**
     * 卫衣数量
     */
    private int hoodieCount = 0;

    /**
     * 成品数量
     */
    private int finishCount = 0;

    /**
     * 聚酯纤维数量
     */
    private int fiberCount = 0;
}