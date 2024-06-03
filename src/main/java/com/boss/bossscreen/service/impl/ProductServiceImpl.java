package com.boss.bossscreen.service.impl;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boss.bossscreen.dao.OperationLogDao;
import com.boss.bossscreen.dao.OrderItemDao;
import com.boss.bossscreen.dao.ProductDao;
import com.boss.bossscreen.dao.ShopDao;
import com.boss.bossscreen.dto.ConditionDTO;
import com.boss.bossscreen.enities.Model;
import com.boss.bossscreen.enities.OperationLog;
import com.boss.bossscreen.enities.Product;
import com.boss.bossscreen.enities.Shop;
import com.boss.bossscreen.service.ProductService;
import com.boss.bossscreen.util.BeanCopyUtils;
import com.boss.bossscreen.util.CommonUtil;
import com.boss.bossscreen.util.PageUtils;
import com.boss.bossscreen.util.ShopeeUtil;
import com.boss.bossscreen.vo.PageResult;
import com.boss.bossscreen.vo.ProductInfoVO;
import com.boss.bossscreen.vo.ProductVO;
import com.boss.bossscreen.vo.SelectVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.boss.bossscreen.constant.OptTypeConst.SYSTEM_LOG;
import static com.boss.bossscreen.constant.RedisPrefixConst.*;

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
    private ProductDao productDao;

    @Autowired
    private OrderItemDao orderItemDao;

    @Autowired
    private ModelServiceImpl modelService;

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private RedisServiceImpl redisService;

    @Autowired
    private OperationLogDao operationLogDao;


    private static final HashMap<String, String> productStatusMap = new HashMap<>();

    private static final List<String> ruleKeys = new ArrayList<>();

    static {
        productStatusMap.put("NORMAL", "已上架");
        productStatusMap.put("BANNED", "禁止");
        productStatusMap.put("UNLIST", "未上架");
        productStatusMap.put("SELLER_DELETE", "卖家删除");
        productStatusMap.put("SHOPEE_DELETE", "平台删除");
        productStatusMap.put("REVIEWING", "审查中");

        ruleKeys.add("itemId");
        ruleKeys.add("itemSku");
        ruleKeys.add("categoryId");
        ruleKeys.add("status");
        ruleKeys.add("createTime");
        ruleKeys.add("price");
        ruleKeys.add("salesVolume");
        ruleKeys.add("salesVolumeOneDays");
        ruleKeys.add("salesVolume7days");
        ruleKeys.add("salesVolume30days");
    }

    @Override
    public void saveOrUpdateProduct() {
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

            // todo 事务嵌套了
            refreshProductByItemId(itemIdList, shopId);
        }
    }

    @Override
    public void refreshProducts(List<Integer> itemIds) {
        StringJoiner sj = new StringJoiner(",");
        for (int i = 0; i < itemIds.size(); i ++) {
            sj.add(String.valueOf(itemIds.get(i)));
        }
        List<Product> products = productDao.selectList(new QueryWrapper<Product>().select("item_id", "shop_id").in("item_id", sj.toString()));

        Map<Long, List<String>> map = new HashMap<>();

        for (Product product : products) {
            long itemId = product.getItemId();
            long shop_id = product.getShopId();

            if (!map.containsKey(shop_id)) {
                List<String> temp = new ArrayList<>();
                temp.add(String.valueOf(itemId));
                map.put(shop_id, temp);
            } else {
                List<String> temp = map.get(shop_id);
                temp.add(String.valueOf(itemId));
                map.put(shop_id, temp);
            }
        }

        for (long shopId : map.keySet()) {
            List<String> oldItemList = map.get(shopId);

            List<String> itemIdList = new ArrayList<>();
            for (int i = 0; i < oldItemList.size(); i += 50) {
                itemIdList.add(String.join(",", oldItemList.subList(i, Math.min(i + 50, oldItemList.size()))));
            }

            // todo 加锁
//            refreshProductByItemId(itemIdList, shopId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshProductByItemId(List<String> itemIdList, long shopId) {
        // 根据每个店铺的 token 和 shopId 获取产品
        List<Product> productList = new CopyOnWriteArrayList<>();
        List<Model> modelList =  new CopyOnWriteArrayList<>();

        // todo 改为全局线程池和 futurelist

        // 开线程池，线程数量为要遍历的对象的长度
        CountDownLatch productCountDownLatch = new CountDownLatch(itemIdList.size());
        ExecutorService productExecutor = Executors.newFixedThreadPool(itemIdList.size());
        CountDownLatch modelCountDownLatch = new CountDownLatch(itemIdList.size());
        ExecutorService modelExecutor = Executors.newFixedThreadPool(itemIdList.size());
        for (String itemId : itemIdList) {

            long finalShopId = shopId;

            CompletableFuture.runAsync(() -> {
                try {
                    String finalAccessToken = shopService.getAccessTokenByShopId(String.valueOf(finalShopId));
                    getProductDetail(itemId, finalAccessToken, finalShopId, productList);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    productCountDownLatch.countDown();
                    log.info("productCountDownLatch===> {}", productCountDownLatch);
                }
            }, productExecutor);

            CompletableFuture.runAsync(() -> {
                try {
                    String[] splitIds = itemId.split(",");
                    for (String splitId : splitIds) {
                        String finalAccessToken = shopService.getAccessTokenByShopId(String.valueOf(finalShopId));
                        modelService.getModel(Long.parseLong(splitId), finalAccessToken, finalShopId, modelList);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    modelCountDownLatch.countDown();
                    log.info("modelCountDownLatch===> {}", modelCountDownLatch);
                }
            }, modelExecutor);
        }

        try {
            productCountDownLatch.await();
            modelCountDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.saveOrUpdateBatch(productList);
        modelService.saveOrUpdateBatch(modelList);
    }

    private void getProductDetail(String itemIds, String token, long shopId, List<Product> productList) {
        try {
            JSONObject result = ShopeeUtil.getProductInfo(token, shopId, itemIds);

            if (result == null || result.getString("error").contains("error")) {
                return;
            }

            JSONArray itemArray = result.getJSONObject("response").getJSONArray("item_list");

            JSONObject itemObject;
            JSONArray imgIdArray;
            JSONArray imgUrlArray;
            for (int i = 0; i < itemArray.size(); i++) {
                itemObject = itemArray.getJSONObject(i);

                imgIdArray = itemObject.getJSONObject("image").getJSONArray("image_id_list");
                imgUrlArray = itemObject.getJSONObject("image").getJSONArray("image_url_list");

                long itemId = itemObject.getLong("item_id");
                Product product = Product.builder()
                        .id(itemId)
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


                String judgeResult = CommonUtil.judgeRedis(redisService,PRODUCT_ITEM + itemId, productList, product, Product.class);
                if (!"".equals(judgeResult)) {
                    JSONArray diffArray = JSON.parseObject(judgeResult).getJSONArray("defectsList");
                    if (diffArray.size() != 0) {
                        StringJoiner joiner = new StringJoiner(",");
                        OperationLog operationLog = new OperationLog();
                        operationLog.setOptType(SYSTEM_LOG);
                        for (int j = 0; j < diffArray.size(); j++) {
                            String key = diffArray.getJSONObject(j).getJSONObject("travelPath").getString("abstractTravelPath");
                            joiner.add(key.substring(key.indexOf(".") + 1, key.length()));
                        }
                        operationLog.setOptDesc("产品 " + itemId + " 字段发生变化：" + joiner.toString());
                        operationLogDao.insert(operationLog);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public PageResult<ProductVO> productListByCondition(ConditionDTO condition) {
        // 查询分类数量
        Integer count = productDao.productCount(condition);
        if (count == 0) {
            return new PageResult<>();
        }
        // 分页查询分类列表
        List<ProductVO> productList = productDao.productList(PageUtils.getLimitCurrent(), PageUtils.getSize(), condition)
                .stream().map(productVO -> {
                    Object redisCategoryObj = redisService.get(CATEGORY + productVO.getCategoryId());
                    if (redisCategoryObj != null) {
                        productVO.setCategoryName(JSONObject.parseObject(redisCategoryObj.toString()).getString("display_category_name"));
                    }
                    productVO.setCreateTime(CommonUtil.timestamp2String((Long) productVO.getCreateTime()));
                    productVO.setStatus(productStatusMap.get(productVO.getStatus()));

                    productVO.setShopName(shopDao.selectOne(new QueryWrapper<Shop>().select("name").eq("shop_id", productVO.getShopId())).getName());

                    // 设置销量
//                    Integer tempCount = orderItemDao.salesVolumeByItemId(productVO.getItemId());
//                    int salesVolume = tempCount == null ? 0 : tempCount;
//                    productVO.setSalesVolume(salesVolume);

                    Product product = BeanCopyUtils.copyObject(productVO, Product.class);
                    product.setCreateTime(CommonUtil.string2Timestamp(String.valueOf(productVO.getCreateTime())));
                    // 判断规则设置产品等级
                    productVO.setGrade(getGrade(product, productVO.getSalesVolume()));

                    return productVO;
                }).collect(Collectors.toList());
        return new PageResult<>(productList, count);
    }

    @Override
    public ProductInfoVO getProductInfo(Long itemId) {

        Product product = productDao.selectOne(new QueryWrapper<Product>().eq("item_id", itemId));

        ProductInfoVO productInfoVO = BeanCopyUtils.copyObject(product, ProductInfoVO.class);
        Object redisCategoryObj = redisService.get(CATEGORY + product.getCategoryId());
        if (redisCategoryObj != null) {
            productInfoVO.setCategoryName(JSONObject.parseObject(redisCategoryObj.toString()).getString("display_category_name"));
        }

        productInfoVO.setCreateTime(CommonUtil.timestamp2String(product.getCreateTime()));
        productInfoVO.setStatus(productStatusMap.get(product.getStatus()));

        productInfoVO.setShopName(shopDao.selectOne(new QueryWrapper<Shop>().eq("shop_id", product.getShopId())).getName());

        // 判断规则设置产品等级
        // 设置销量
        Integer tempCount = orderItemDao.salesVolumeByItemId(product.getItemId());
        int salesVolume = tempCount == null ? 0 : tempCount;
        productInfoVO.setGrade(getGrade(product, salesVolume));

        productInfoVO.setModelVOList(modelService.getModelVOListByItemId(itemId));

        return productInfoVO;
    }

    @Override
    public List<SelectVO> getCategorySelect() {
        List<SelectVO> list = new ArrayList<>();
        List<Product> categoryId = productDao.selectList(new QueryWrapper<Product>().select("category_id").groupBy("category_id"));
        for (Product product : categoryId) {
            Long id = product.getCategoryId();
            Object redisCategoryObj = redisService.get(CATEGORY + product.getCategoryId());
            if (redisCategoryObj == null) {
                continue;
            }
            String name = JSONObject.parseObject(redisCategoryObj.toString()).getString("display_category_name");
            SelectVO vo = SelectVO.builder()
                    .key(id)
                    .value(name).build();
            list.add(vo);
        }
        return list;
    }

    @Override
    public List<SelectVO> getStatusSelect() {
        List<SelectVO> list = new ArrayList<>();
        for(String key : productStatusMap.keySet()){
            SelectVO vo = SelectVO.builder()
                    .key(key)
                    .value(productStatusMap.get(key)).build();
            list.add(vo);
        }
        return list;
    }


    public String getGrade(Product product, int salesVolume) {

        String grade = "";
        boolean allOrNot;
        JSONObject ruleData;
        // 满足规则次数
        int ruleCount = 0;
        Set<String> keys = redisService.keys(RULE + "*");
        for (String key : keys) {
            JSONObject object = JSONObject.parseObject(redisService.get(key).toString());

            ruleData = object.getJSONObject("ruleData");
            if (ruleData == null || ruleData.keySet().size() == 0) {
                continue;
            }
            grade = object.getString("grade");
            allOrNot = object.getBoolean("allOrNot");

            // true：全部满足
            // false：满足任一条件
            // 满足条件次数
            int count = getSatisfactionCount(product, ruleData, allOrNot, salesVolume);
            // 全部满足：满足条件次数 = 全部条件个数
            if (allOrNot && count == ruleData.keySet().size()) {
                ruleCount ++;
            } else if (!allOrNot && count > 0) {
                // 满足任一条件：满足条件次数 > 0
                ruleCount ++;
            }

            // 满足超过一个规则直接 break
            if (ruleCount > 1) {
                grade = "!";
                break;
            }
        }

        return grade;
    }

    private int getSatisfactionCount(Product product, JSONObject ruleData, boolean allOrNot, int salesVolume) {
        Date nowDate = new Date();

        int count = 0;

        if (ruleData.containsKey("itemId") && ruleData.getJSONObject("itemId") != null) {
            if (ruleData.getJSONObject("itemId").getString("value").equals(String.valueOf(product.getItemId()))) {
                count ++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("itemSku") && ruleData.getJSONObject("itemSku") != null) {
            if (ruleData.getJSONObject("itemSku").getString("value").equals(String.valueOf(product.getItemSku()))) {
                count ++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("categoryId") && ruleData.getJSONObject("categoryId") != null) {
            if (ruleData.getJSONObject("categoryId").getString("value").equals(String.valueOf(product.getCategoryId()))) {
                count ++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("status") && ruleData.getJSONObject("status") != null) {
            if (ruleData.getJSONObject("status").getString("value").equals(String.valueOf(product.getStatus()))) {
                count ++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("createTime") && ruleData.getJSONObject("createTime") != null) {
            JSONObject object = ruleData.getJSONObject("createTime");
            LocalDateTime createTime = CommonUtil.timestamp2LocalDateTime(product.getCreateTime());
            LocalDateTime startTime = CommonUtil.string2LocalDateTime(object.getString("startTime"));
            LocalDateTime endTime = CommonUtil.string2LocalDateTime(object.getString("endTime"));

            if ((createTime.isAfter(startTime) && createTime.isBefore(endTime)) || createTime.equals(startTime) || createTime.equals(endTime)) {
                count ++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("price") && ruleData.getJSONObject("price") != null) {
            JSONObject object = ruleData.getJSONObject("price");
            // 最小价格
            BigDecimal minPrice = new BigDecimal(object.getString("minPrice"));
            // 最大价格
            BigDecimal maxPrice = new BigDecimal(object.getString("maxPrice"));

            BigDecimal itemMinPrice = orderItemDao.itemMinPrice(product.getItemId());

            if (itemMinPrice.compareTo(minPrice) >= 0 && itemMinPrice.compareTo(maxPrice) <= 0) {
                count++;
            }

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("salesVolume") && ruleData.getJSONObject("salesVolume") != null) {
            JSONObject object = ruleData.getJSONObject("salesVolume");
            int ruleValue = Integer.valueOf(object.getString("value"));
            String ruleType = object.getString("type");

            count = judgeIntegerRange(salesVolume, ruleValue, ruleType, count);

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("salesVolumeOneDays") && ruleData.getJSONObject("salesVolumeOneDays") != null) {
            JSONObject object = ruleData.getJSONObject("salesVolumeOneDays");
            int ruleValue = Integer.valueOf(object.getString("value"));
            String date = object.getString("date");
            String ruleType = object.getString("type");

            long time = CommonUtil.string2Timestamp(date);

            Integer tempCount = orderItemDao.itemCountByCreateTime(product.getItemId(), time);
            int salesVolumeOneDaysCount = tempCount == null ? 0 : tempCount;

            count = judgeIntegerRange(salesVolumeOneDaysCount, ruleValue, ruleType, count);

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("salesVolume7days") && ruleData.getJSONObject("salesVolume7days") != null) {
            JSONObject object = ruleData.getJSONObject("salesVolume7days");
            // 值
            int ruleValue = Integer.valueOf(object.getString("value"));
            // 符号类型
            String ruleType = object.getString("type");

            // 当前时间 - 7 天的时间戳
            long startTime = DateUtil.offsetDay(nowDate, -7).getTime();
            long endTime = nowDate.getTime();

            Integer tempCount = orderItemDao.itemCountByCreateTimeRange(product.getItemId(), startTime, endTime);
            int salesVolume7daysCount = tempCount == null ? 0 : tempCount;

            count = judgeIntegerRange(salesVolume7daysCount, ruleValue, ruleType, count);

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        if (ruleData.containsKey("salesVolume30days") && ruleData.getJSONObject("salesVolume30days") != null) {
            JSONObject object = ruleData.getJSONObject("salesVolume30days");
            int ruleValue = Integer.valueOf(object.getString("value"));
            String ruleType = object.getString("type");

            // 当前时间 - 30 天的时间戳
            long startTime = DateUtil.offsetDay(nowDate, -30).getTime();
            long endTime = nowDate.getTime();

            Integer tempCount = orderItemDao.itemCountByCreateTimeRange(product.getItemId(), startTime, endTime);
            int salesVolume30daysCount = tempCount == null ? 0 : tempCount;

            count = judgeIntegerRange(salesVolume30daysCount, ruleValue, ruleType, count);

            if (returnOrNot(allOrNot, count)) {
                return count;
            }
        }

        return count;
    }

    private int judgeIntegerRange(int salesVolume, int ruleValue, String type, int count) {
        if ("=".equals(type) && salesVolume == ruleValue) {
            count++;
        } else if ("<=".equals(type) && salesVolume <= ruleValue) {
            count++;
        } else if (">=".equals(type) && salesVolume >= ruleValue) {
            count++;
        } else if (">".equals(type) && salesVolume > ruleValue) {
            count++;
        } else if ("<".equals(type) && salesVolume < ruleValue) {
            count++;
        }

        return count;
    }

    private boolean returnOrNot(boolean allOrNot, int count) {
        if (!allOrNot && count > 0) {
            return true;
        }
        return false;
    }
}
