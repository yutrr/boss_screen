package com.boss.bossscreen.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boss.bossscreen.dao.ProductDao;
import com.boss.bossscreen.dao.ShopDao;
import com.boss.bossscreen.enities.Model;
import com.boss.bossscreen.enities.Product;
import com.boss.bossscreen.enities.Shop;
import com.boss.bossscreen.service.ProductService;
import com.boss.bossscreen.util.ShopeeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */

@Service
@Slf4j
public class ProductServiceImpl extends ServiceImpl<ProductDao, Product> implements ProductService {

    @Autowired
    private ShopDao shopDao;

    @Autowired
    private ModelServiceImpl modelService;

    // todo 优化下拉
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveOrUpdateProduct() {
        // 遍历所有未冻结店铺获取 token 和 shopId
        QueryWrapper<Shop> shopQueryWrapper = new QueryWrapper<>();
        shopQueryWrapper.select("shop_id", "access_token").eq("status", "1");
        List<Shop> shopList = shopDao.selectList(shopQueryWrapper);

        // 根据每个店铺的 token 和 shopId 获取产品
        List<Product> productList = new ArrayList<>();
        List<Model> modelList =  new ArrayList<>();
        long shopId;
        String accessToken = "";
        JSONObject result = new JSONObject();
        for (Shop shop : shopList) {
            shopId = shop.getShopId();
            accessToken = shop.getAccessToken();

            // todo 集合查询
            result = ShopeeUtil.getProducts(accessToken, shopId);

            if (result.getString("error").contains("error")) {
                continue;
            }

            JSONArray itemArray = result.getJSONObject("response").getJSONArray("item");
            if (itemArray.size() == 0) {
                continue;
            }


            for (int i = 0; i < itemArray.size(); i++) {
                JSONObject itemObject = itemArray.getJSONObject(i);
                long itemId = itemObject.getLong("item_id");
                productList = getProductDetail(itemId, accessToken, shopId, productList);
                modelList = modelService.getModel(itemId, accessToken, shopId, modelList);
            }
        }
        // todo 检测入库
        System.out.println(JSONArray.toJSONString(productList));
        this.saveBatch(productList);
        System.out.println(JSONArray.toJSONString(modelList));
        modelService.saveBatch(modelList);
    }

    private List<Product> getProductDetail(long itemId, String token, long shopId, List<Product> productList) {
        JSONObject result = ShopeeUtil.getProductInfo(token, shopId, itemId);

        if (result.getString("error").contains("error")) {
            return productList;
        }

        JSONArray itemArray = result.getJSONObject("response").getJSONArray("item_list");

        JSONObject itemObject;
        JSONArray imgIdArray = new JSONArray();
        JSONArray imgUrlArray = new JSONArray();
        for (int i = 0; i < itemArray.size(); i++) {
            itemObject = itemArray.getJSONObject(i);

            imgIdArray = itemObject.getJSONObject("image").getJSONArray("image_id_list");
            imgUrlArray = itemObject.getJSONObject("image").getJSONArray("image_url_list");

            Product product = Product.builder()
                    .shopId(shopId)
                    .itemId(itemId)
                    .itemName(itemObject.getString("item_name"))
                    .categoryId(itemObject.getLong("category_id"))
                    .createTime(itemObject.getLong("create_time"))
                    .updateTime(itemObject.getLong("update_time"))
                    .itemSku(itemObject.getString("item_sku"))
                    .mainImgUrl(imgUrlArray.getString(0))
                    .mainImgId(imgIdArray.getString(0))
                    .status(itemObject.getString("item_status"))
                    .build();


            imgIdArray = itemObject.getJSONObject("image").getJSONArray("image_id_list");
            if (imgIdArray.size() > 0) {
                product.setMainImgId(imgIdArray.getString(0));
            }
            imgUrlArray = itemObject.getJSONObject("image").getJSONArray("image_url_list");
            if (imgUrlArray.size() > 0) {
                product.setMainImgUrl(imgUrlArray.getString(0));
            }

            productList.add(product);


        }

        return productList;
    }


}
