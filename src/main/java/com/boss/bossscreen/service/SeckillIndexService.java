package com.boss.bossscreen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.boss.bossscreen.enities.excelEnities.SeckillIndex;
import org.springframework.web.multipart.MultipartFile;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/10
 */
public interface SeckillIndexService extends IService<SeckillIndex>  {

    void importExcel(long shopId, MultipartFile file);
}
