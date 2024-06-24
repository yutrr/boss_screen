package com.boss.client.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.common.enities.excelEnities.FlowOverview;
import org.springframework.web.multipart.MultipartFile;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/10
 */
public interface FlowOverviewService extends IService<FlowOverview>  {

    void importExcel(long shopId, MultipartFile file);
}