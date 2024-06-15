package com.boss.bossscreen.service.impl;

import cn.hutool.core.thread.ExecutorBuilder;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boss.bossscreen.dao.ProductExtraInfoDao;
import com.boss.bossscreen.dao.ShopDao;
import com.boss.bossscreen.enities.ProductExtraInfo;
import com.boss.bossscreen.enities.Shop;
import com.boss.bossscreen.service.ProductExtraInfoService;
import com.boss.bossscreen.util.CommonUtil;
import com.boss.bossscreen.util.ShopeeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */

@Service
@Slf4j
public class ProductExtraInfoServiceImpl extends ServiceImpl<ProductExtraInfoDao, ProductExtraInfo> implements ProductExtraInfoService {

    @Autowired
    private ShopDao shopDao;

    @Autowired
    private ShopServiceImpl shopService;


    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ProductExtraInfoServiceImpl(DataSourceTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveOrUpdateProductExtraInfo() {
        // 遍历所有未冻结店铺获取 token 和 shopId
        QueryWrapper<Shop> shopQueryWrapper = new QueryWrapper<>();
        shopQueryWrapper.select("shop_id").eq("status", "1");
        List<Shop> shopList = shopDao.selectList(shopQueryWrapper);

        long shopId;
        String accessToken;

        for (Shop shop : shopList) {
            shopId = shop.getShopId();
            accessToken = shopService.getAccessTokenByShopId(String.valueOf(shopId));

            List<String> itemIds = ShopeeUtil.getProducts(accessToken, shopId, 0, new ArrayList<>());

            if (itemIds == null || itemIds.isEmpty()) {
                continue;
            }

            List<String> itemIdList = new ArrayList<>();
            for (int i = 0; i < itemIds.size(); i += 50) {
                itemIdList.add(String.join(",", itemIds.subList(i, Math.min(i + 50, itemIds.size()))));
            }

            refreshProductExtraInfoByItemId(itemIdList, shopId);
        }
    }

    public void refreshProductExtraInfoByItemId(List<String> itemIdList, long shopId) {
        List<ProductExtraInfo> productExtraInfoList = new CopyOnWriteArrayList<>();

        List<CompletableFuture<Void>> productExtraInfoFutures = itemIdList.stream()
                .map(itemId -> {
                    long finalShopId = shopId;
                    return CompletableFuture.runAsync(() -> {
                        String accessToken = shopService.getAccessTokenByShopId(String.valueOf(finalShopId));
                        getProductExtraInfo(itemId, accessToken, finalShopId, productExtraInfoList);
                    }, ExecutorBuilder.create().setCorePoolSize(itemIdList.size()).build());
                }).collect(Collectors.toList());

        CompletableFuture.allOf(productExtraInfoFutures.toArray(new CompletableFuture[0])).join();


        List<List<ProductExtraInfo>> splitProduct = CommonUtil.splitListBatches(productExtraInfoList, 100);
        List<CompletableFuture<Void>> insertProductFutures = new ArrayList<>();
        for (List<ProductExtraInfo> batch : splitProduct) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
//                    TransactionTemplate transactionTemplate = new TransactionTemplate();
                    transactionTemplate.executeWithoutResult(status -> {
                        this.saveOrUpdateBatch(batch);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, ExecutorBuilder.create().setCorePoolSize(splitProduct.size()).build());

            insertProductFutures.add(future);
        }
    }

    private void getProductExtraInfo(String itemIds, String token, long shopId, List<ProductExtraInfo> productList) {
        JSONObject result = ShopeeUtil.getProductExtraInfo(token, shopId, itemIds);

        if (result == null || result.getString("error").contains("error")) {
            return;
        }

        JSONArray itemArray = result.getJSONObject("response").getJSONArray("item_list");

        JSONObject itemObject;
        for (int i = 0; i < itemArray.size(); i++) {
            itemObject = itemArray.getJSONObject(i);

            ProductExtraInfo productExtraInfo = ProductExtraInfo.builder()
                    .id(itemObject.getLong("item_id"))
                    .itemId(itemObject.getLong("item_id"))
                    .sale(itemObject.getInteger("sale"))
                    .views(itemObject.getInteger("views"))
                    .likes(itemObject.getInteger("likes"))
                    .ratingStar(itemObject.getFloat("rating_star"))
                    .commentCount(itemObject.getInteger("comment_count"))
                    .build();

            productList.add(productExtraInfo);
        }
    }
}