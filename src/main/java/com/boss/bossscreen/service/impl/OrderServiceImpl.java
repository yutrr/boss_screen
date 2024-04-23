package com.boss.bossscreen.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boss.bossscreen.dao.*;
import com.boss.bossscreen.dto.ConditionDTO;
import com.boss.bossscreen.enities.*;
import com.boss.bossscreen.service.OrderService;
import com.boss.bossscreen.util.BeanCopyUtils;
import com.boss.bossscreen.util.CommonUtil;
import com.boss.bossscreen.util.PageUtils;
import com.boss.bossscreen.util.ShopeeUtil;
import com.boss.bossscreen.vo.OrderEscrowInfoVO;
import com.boss.bossscreen.vo.OrderEscrowItemVO;
import com.boss.bossscreen.vo.OrderEscrowVO;
import com.boss.bossscreen.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.boss.bossscreen.constant.RedisPrefixConst.*;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/11
 */

@Service
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderDao, Order> implements OrderService {

    @Autowired
    private ShopDao shopDao;

    @Autowired
    private OrderDao orderDao;

    @Autowired
    private OrderItemDao orderItemDao;

    @Autowired
    private EscrowInfoDao escrowInfoDao;

    @Autowired
    private EscrowItemDao escrowItemDao;

    @Autowired
    private RedisServiceImpl redisService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveOrUpdateOrder(String orderSnStartTime) {
        long logStart =  System.currentTimeMillis();

        // 定义日期格式
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00");

        // 将字符串转换为LocalDate对象
        LocalDate startTime = LocalDate.parse(orderSnStartTime, formatter);

        LocalDate endTime = LocalDate.from(LocalDateTime.now());

        // 遍历所有未冻结店铺获取 token 和 shopId
        QueryWrapper<Shop> shopQueryWrapper = new QueryWrapper<>();
        shopQueryWrapper.select("shop_id", "access_token").eq("status", "1");
        List<Shop> shopList = shopDao.selectList(shopQueryWrapper);

        // 根据每个店铺的 token 和 shopId 获取产品
        List<Order> ordertList = new CopyOnWriteArrayList<>();
        List<Order> updateOrderList = new CopyOnWriteArrayList<>();
        List<OrderItem> orderItemList =  new CopyOnWriteArrayList<>();
        List<OrderItem> updateOrderItemList = new CopyOnWriteArrayList<>();
        List<EscrowInfo> escrowInfoList =  new CopyOnWriteArrayList<>();
        List<EscrowInfo> updateEscrowInfoList = new CopyOnWriteArrayList<>();
        List<EscrowItem> escrowItemList = new CopyOnWriteArrayList<>();
        List<EscrowItem> updateEscrowItemList = new CopyOnWriteArrayList<>();
        long shopId;
        String accessToken;
        JSONObject result;
        for (Shop shop : shopList) {
            shopId = shop.getShopId();
            accessToken = shop.getAccessToken();

            List<LocalDate[]> timeList = splitIntoEvery15DaysTimestamp(startTime, endTime);
            List<String> orderSnList = new ArrayList<>();
            for (LocalDate[] time : timeList) {
                long start = time[0].atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000L;
                long end = time[1].atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000L;
                List<String> object = ShopeeUtil.getOrderList(accessToken, shopId, 0, new ArrayList<>(), start, end);
                orderSnList.addAll(object);
            }
            List<String> newOrderSnList = new ArrayList<>();
            for (int i = 0; i < orderSnList.size(); i += 50) {
                newOrderSnList.add(String.join(",", orderSnList.subList(i, Math.min(i + 50, orderSnList.size()))));
            }

            CountDownLatch orderCountDownLatch = new CountDownLatch(newOrderSnList.size());
            // 开线程池，线程数量为要遍历的对象的长度
            ExecutorService orderExecutor = Executors.newFixedThreadPool(newOrderSnList.size());

            CountDownLatch escrowCountDownLatch = new CountDownLatch(newOrderSnList.size());
            // 开线程池，线程数量为要遍历的对象的长度
            ExecutorService escrowExecutor = Executors.newFixedThreadPool(newOrderSnList.size());
            for (String orderSn : newOrderSnList) {
                String finalAccessToken = accessToken;
                long finalShopId = shopId;

                CompletableFuture.runAsync(() -> {
                    try {
                        JSONObject orderObject = ShopeeUtil.getOrderDetail(finalAccessToken, finalShopId, orderSn);
                        JSONArray orderArray = orderObject.getJSONObject("response").getJSONArray("order_list");
                        JSONObject orderDetailObject;
                        for (int j = 0; j < orderArray.size(); j++) {
                            orderDetailObject = orderArray.getJSONObject(j);
                            getOrderDetail(orderDetailObject, ordertList, finalShopId, updateOrderList);
                            getOrderItem(orderDetailObject, orderItemList, updateOrderItemList);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        orderCountDownLatch.countDown();
                        System.out.println("orderCountDownLatch===> " + orderCountDownLatch);
                    }
                }, orderExecutor);

                CompletableFuture.runAsync(() -> {
                    try {
                        String[] splitOrderSns = orderSn.split(",");
                        for (String sn : splitOrderSns) {
                            JSONObject escrowResult = ShopeeUtil.getEscrowDetail(finalAccessToken, finalShopId, sn);
                            JSONObject escrowInfoObject = escrowResult.getJSONObject("response");
                            if (escrowResult.getString("error").contains("error") && escrowInfoObject == null) {
                                return;
                            }
                            JSONObject oderIncomeObject = escrowInfoObject.getJSONObject("order_income");
                            saveEscrowInfoByOrderSn(oderIncomeObject, sn, escrowInfoList, updateEscrowInfoList);
                            saveEscrowItem(oderIncomeObject, sn, escrowItemList, updateEscrowItemList);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        escrowCountDownLatch.countDown();
                        System.out.println("escrowCountDownLatch===> " + escrowCountDownLatch);
                    }
                }, escrowExecutor);
            }

            try {
                orderCountDownLatch.await();
                escrowCountDownLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.info("更新订单耗时： {}秒", (System.currentTimeMillis() - logStart) / 1000);

        System.out.println("orderList===>" + JSONArray.toJSONString(ordertList));
//        this.saveBatch(ordertList);
        System.out.println("updateOrderList===>" + JSONArray.toJSONString(updateOrderList));
//        this.updateBatchById(updateOrderList);
        System.out.println("orderItemList===>" + JSONArray.toJSONString(orderItemList));
//        orderItemService.saveBatch(orderItemList);
        System.out.println("updateOrderItemList===>" + JSONArray.toJSONString(updateOrderItemList));
//        orderItemService.updateBatchById(updateOrderItemList);
        System.out.println("escrowInfoList===>" + JSONArray.toJSONString(escrowInfoList));
//        escrowItemService.saveBatch(escrowInfoList);
        System.out.println("updateEscrowInfoList===>" + JSONArray.toJSONString(updateEscrowInfoList));
//        escrowItemService.updateBatchById(updateEscrowInfoList);
        System.out.println("escrowItemList===>" + JSONArray.toJSONString(escrowItemList));
//        escrowItemService.saveBatch(escrowItemList);
        System.out.println("updateEscrowItemList===>" + JSONArray.toJSONString(updateEscrowItemList));
//        escrowItemService.updateBatchById(updateEscrowItemList);
    }

    private List<LocalDate[]> splitIntoEvery15DaysTimestamp(LocalDate startDate, LocalDate endDate) {
        List<LocalDate[]> timestampPairs = new ArrayList<>();
        while (!startDate.isAfter(endDate)) {
            LocalDate endOfSplitDate = startDate.plusDays(14);
            if (endOfSplitDate.isAfter(endDate)) {
                endOfSplitDate = endDate;
            }
            LocalDate[] pair = new LocalDate[]{
                    startDate,
                    endOfSplitDate
            };
            timestampPairs.add(pair);
            startDate = endOfSplitDate.plusDays(1);
        }
        return timestampPairs;
    }

    private void getOrderDetail(JSONObject orderObject, List<Order> ordertList, Long shopId, List<Order> updateOrderList) {
        String orderSn = orderObject.getString("order_sn");
        Order order = Order.builder()
                .shopId(shopId)
                .createTime(orderObject.getLong("create_time"))
                .updateTime(orderObject.getLong("update_time"))
                .orderSn(orderSn)
                .status(orderObject.getString("order_status"))
                .payTime(orderObject.getLong("pay_time"))
                .buyerUerId(orderObject.getLong("buyer_user_id"))
                .buyerUserName(orderObject.getString("buyer_username"))
                .cancelReason(orderObject.getString("cancel_reason"))
                .cancelBy(orderObject.getString("cancel_by"))
                .buyerCancelReason(orderObject.getString("buyer_cancel_reason"))
                .build();

        JSONArray packageArray = orderObject.getJSONArray("package_list");
        if (packageArray != null && packageArray.size() > 0) {
            order.setPackageNumber(packageArray.getJSONObject(0).getString("package_number"));
        }

        CommonUtil.judgeRedis(redisService, ORDER + orderSn, ordertList,updateOrderList, order, Order.class);

    }

    private void getOrderItem(JSONObject orderObject, List<OrderItem> orderItemList, List<OrderItem> updateOrderItemList) {
        JSONArray itemList = orderObject.getJSONArray("item_list");
        JSONObject itemObject;
        for (int i = 0; i < itemList.size(); i++) {
            itemObject = itemList.getJSONObject(i);

            String orderSn = orderObject.getString("order_sn");
            long itemId = itemObject.getLong("item_id");
            long modelId = itemObject.getLong("model_id");
            OrderItem orderItem = OrderItem.builder()
                    .orderSn(orderSn)
                    .itemId(itemId)
                    .itemName(itemObject.getString("item_name"))
                    .itemSku(itemObject.getString("item_sku"))
                    .modelId(modelId)
                    .modelName(itemObject.getString("model_name"))
                    .modelSku(itemObject.getString("model_sku"))
                    .count(itemObject.getInteger("model_quantity_purchased"))
                    .promotionId(itemObject.getLong("promotion_id"))
                    .promotionType(itemObject.getString("promotion_type"))
                    .build();

            JSONArray packageArray = orderObject.getJSONArray("package_list");
            if (packageArray != null && packageArray.size() > 0) {
                orderItem.setPackageNumber(packageArray.getJSONObject(0).getString("package_number"));
            }

            JSONObject imageInfoArray = itemObject.getJSONObject("image_info");
            if (imageInfoArray != null/* && imageInfoArray.size() > 0*/) {
//                orderItem.setImageUrl(imageInfoArray.getJSONObject(0).getString("image_url"));
                orderItem.setImageUrl(imageInfoArray.getString("image_url"));
            }

            CommonUtil.judgeRedis(redisService, ORDER_ITEM_MODEL + orderSn + "_" + itemId + "_" + modelId, orderItemList, updateOrderItemList, orderItem, OrderItem.class);
        }

    }

    public void saveEscrowInfoByOrderSn(JSONObject orderIncomeObject, String orderSn, List<EscrowInfo> escrowInfoList, List<EscrowInfo> updateEscrowInfoList) {

        EscrowInfo escrowInfo = EscrowInfo.builder()
                .orderSn(orderSn)
                .buyerUserName(orderIncomeObject.getString("buyer_user_name"))
                .buyerTotalAmount(orderIncomeObject.getBigDecimal("buyer_total_amount"))
                .buyerPaidShippingFee(orderIncomeObject.getBigDecimal("buyer_paid_shipping_fee"))
                .actualShippingFee(orderIncomeObject.getBigDecimal("actual_shipping_fee"))
                .escrowAmount(orderIncomeObject.getBigDecimal("escrow_amount"))
                .build();

        String redisKey = ESCROW + orderSn;
        CommonUtil.judgeRedis(redisService, redisKey, escrowInfoList, updateEscrowInfoList, escrowInfo, EscrowInfo.class);
    }

    public void saveEscrowItem(JSONObject oderIncomeObject, String orderSn, List<EscrowItem> escrowItemList, List<EscrowItem> updateEscrowItemList) {
        JSONArray items = oderIncomeObject.getJSONArray("items");
        JSONObject itemObject;
        for (int i = 0; i < items.size(); i++) {
            itemObject = items.getJSONObject(i);
            long itemId = itemObject.getLong("item_id");
            long modelId = itemObject.getLong("model_id");
            EscrowItem escrowItem = EscrowItem.builder()
                    .orderSn(orderSn)
                    .itemId(itemId)
                    .itemName(itemObject.getString("item_name"))
                    .itemSku(itemObject.getString("item_sku"))
                    .modelId(modelId)
                    .modelName(itemObject.getString("model_name"))
                    .modelSku(itemObject.getString("model_sku"))
                    .count(itemObject.getInteger("quantity_purchased"))
                    .originalPrice(itemObject.getBigDecimal("original_price"))
                    .sellingPrice(itemObject.getBigDecimal("selling_price"))
                    .discountedPrice(itemObject.getBigDecimal("discounted_price"))
                    .sellerDiscount(itemObject.getBigDecimal("seller_discount"))
                    .activityId(itemObject.getLong("activity_id"))
                    .activityType(itemObject.getString("activity_type"))
                    .build();

            CommonUtil.judgeRedis(redisService, ESCROW_ITEM_MODEL + orderSn + "_" + itemId + "_" + modelId, escrowItemList, updateEscrowItemList, escrowItem, EscrowItem.class);

        }
    }

    public PageResult<OrderEscrowVO> orderListByCondition(ConditionDTO condition) {
        // 查询分类数量
        Integer count = orderDao.orderCount(condition);
        if (count == 0) {
            return new PageResult<>();
        }
        // 分页查询分类列表
        List<OrderEscrowVO> orderEscrowVOList = orderDao.orderList(PageUtils.getLimitCurrent(), PageUtils.getSize(), condition).stream().map(object -> {
            List<OrderItem> orderItemList = orderItemDao.selectList(new QueryWrapper<OrderItem>().eq("order_sn", object.getOrderSn()));
            // T恤数量
            int tShirtCount = 0;
            // 双面数量
            int doubleCount = 0;
            // 短款T恤数量
            int shortCount = 0;
            // 卫衣数量
            int hoodieCount = 0;
            // 成品数量
            int finishCount = 0;
            // 聚酯纤维数量
            int fiberCount = 0;

            String modelSku;
            String modelName;
            int clothesCount = 0;
            OrderItem orderItem;
            for (int i = 0; i < orderItemList.size(); i++) {
                orderItem = orderItemList.get(i);
                modelSku = orderItem.getModelSku().toLowerCase();
                clothesCount = orderItem.getCount();
                if (modelSku.contains("100%cotton")) {
                    tShirtCount += clothesCount;
                } else if (modelSku.contains("short")) {
                    shortCount += clothesCount;
                } else if (modelSku.contains("hoodie")) {
                    hoodieCount += clothesCount;
                } else if (modelSku.contains("成品")) {
                    finishCount += clothesCount;
                } else if (modelSku.contains("t-shirt")) {
                    fiberCount += clothesCount;
                } else {
                    tShirtCount += clothesCount;
                }

                modelName = orderItem.getModelName();
                if (!"notsure".equals(modelName) && isEnglish(modelName.substring(0, 1)) && isEnglish(modelName.substring(1, 2))) {
                    doubleCount += 1;
                }
            }
            object.setTShirtCount(tShirtCount);
            object.setDoubleCount(doubleCount);
            object.setShortCount(shortCount);
            object.setHoodieCount(hoodieCount);
            object.setFinishCount(finishCount);
            object.setFiberCount(fiberCount);

            return object;
        }).collect(Collectors.toList());
        return new PageResult<>(orderEscrowVOList, count);
    }

    public OrderEscrowInfoVO getOrderInfo(String orderSn) {

        Order order = orderDao.selectOne(new QueryWrapper<Order>().eq("order_sn", orderSn));

        OrderEscrowInfoVO orderEscrowInfoVO = BeanCopyUtils.copyObject(order, OrderEscrowInfoVO.class);

        EscrowInfo escrowInfo = escrowInfoDao.selectOne(new QueryWrapper<EscrowInfo>().eq("order_sn", orderSn));
        if (escrowInfo != null) {
            orderEscrowInfoVO.setBuyerTotalAmount(escrowInfo.getBuyerTotalAmount());
            orderEscrowInfoVO.setBuyerPaidShippingFee(escrowInfo.getBuyerPaidShippingFee());
            orderEscrowInfoVO.setActualShippingFee(escrowInfo.getActualShippingFee());
            orderEscrowInfoVO.setEscrowAmount(escrowInfo.getEscrowAmount());
        }

        List<OrderEscrowItemVO> orderEscrowItemVOList = orderItemDao.selectList(new QueryWrapper<OrderItem>().eq("order_sn", orderSn)).stream().map(
                orderItem -> {
                    OrderEscrowItemVO orderEscrowItemVO = BeanCopyUtils.copyObject(orderItem, OrderEscrowItemVO.class);

                    EscrowItem escrowItem = escrowItemDao.selectOne(new QueryWrapper<EscrowItem>()
                            .eq("order_sn", orderSn)
                            .eq("item_id", orderEscrowItemVO.getItemId())
                            .eq("model_id", orderEscrowItemVO.getModelId()));
                    if (escrowItem != null) {
                        orderEscrowItemVO.setOriginalPrice(escrowItem.getOriginalPrice());
                        orderEscrowItemVO.setSellingPrice(escrowItem.getSellingPrice());
                        orderEscrowItemVO.setDiscountedPrice(escrowItem.getDiscountedPrice());
                        orderEscrowItemVO.setSellerDiscount(escrowItem.getSellerDiscount());
                        orderEscrowItemVO.setActivityId(escrowItem.getActivityId());
                        orderEscrowItemVO.setActivityType(escrowItem.getActivityType());
                    }

                    // todo 成本，利润，利润率计算

                    return orderEscrowItemVO;
                }
        ).collect(Collectors.toList());

        orderEscrowInfoVO.setOrderEscrowItemVOList(orderEscrowItemVOList);

        return orderEscrowInfoVO;
    }

    private static final Pattern ENGLISH_PATTERN = Pattern.compile("^[a-zA-Z]+$");
    private boolean isEnglish(String str) {
        if (str == null) {
            return false;
        }
        Matcher matcher = ENGLISH_PATTERN.matcher(str);
        return matcher.matches();
    }
}
