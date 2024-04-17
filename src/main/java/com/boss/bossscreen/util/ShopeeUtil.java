package com.boss.bossscreen.util;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.boss.bossscreen.dto.MainAccountAuthDTO;
import com.boss.bossscreen.dto.ShopAuthDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/4/8
 */


@Component
@Slf4j
public class ShopeeUtil {



    /**
     * 获得授权链接签名
     * @param partner_id
     * @param path
     * @param timest
     * @param tmp_partner_key
     * @return
     */
    public static String getAuthSign(long partner_id, String path, long timest, String tmp_partner_key) {
        String tmp_base_string = String.format("%s%s%s", partner_id, path, timest);
        byte[] partner_key;
        byte[] base_string;
        String sign = "";
        try {
            base_string = tmp_base_string.getBytes("UTF-8");
            partner_key = tmp_partner_key.getBytes("UTF-8");
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(partner_key, "HmacSHA256");
            mac.init(secret_key);
            sign = String.format("%064x",new BigInteger(1,mac.doFinal(base_string)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sign;
    }

    /**
     * 获取 token 签名
     * @param partner_id
     * @param path
     * @param timest
     * @param tmp_partner_key
     * @return
     */
    public static BigInteger getTokenSign(long partner_id, String path, long timest, String tmp_partner_key) {
        String tmp_base_string = String.format("%s%s%s", partner_id, path, timest);
        byte[] partner_key;
        byte[] base_string;
        BigInteger sign = null;
        try {
            base_string = tmp_base_string.getBytes("UTF-8");
            partner_key = tmp_partner_key.getBytes("UTF-8");
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(partner_key, "HmacSHA256");
            mac.init(secret_key);
            sign = new BigInteger(1,mac.doFinal(base_string));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sign;
    }

    public static BigInteger getShopTokenSign(long partner_id, String path, long timest, String accessToken, long shopId, String tmp_partner_key) {
        String tmp_base_string = String.format("%s%s%s%s%s", partner_id, path, timest, accessToken, shopId);
        byte[] partner_key;
        byte[] base_string;
        BigInteger sign = null;
        try {
            base_string = tmp_base_string.getBytes("UTF-8");
            partner_key = tmp_partner_key.getBytes("UTF-8");
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(partner_key, "HmacSHA256");
            mac.init(secret_key);
            sign = new BigInteger(1,mac.doFinal(base_string));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sign;
    }

    // 生成授权链接
    public static String getAuthUrl(String type){
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/shop/auth_partner";
        String redirect_url = "shop".equals(type) ? ShopAuthDTO.getRedirectUrl() : MainAccountAuthDTO.getRedirectUrl();
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        String sign = getAuthSign(partner_id,path,timest,tmp_partner_key);
        return host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s&redirect=%s", partner_id,timest, sign, redirect_url);
    }

    //shop request for access token for the first time
    // 获取店铺账号token
    public static JSONObject getShopAccessToken(String code,long shop_id) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/auth/token/get";
        BigInteger sign = getTokenSign(ShopAuthDTO.getPartnerId(), path,timest,ShopAuthDTO.getTempPartnerKey());
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s", ShopAuthDTO.getPartnerId(),timest, String.format("%032x",sign));
        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("code",code);
        paramMap.put("shop_id",shop_id);
        paramMap.put("partner_id",ShopAuthDTO.getPartnerId());
        String result = HttpRequest.post(tmp_url)
                .header(Header.ACCEPT, "application/json")
                .header(Header.CONTENT_TYPE, "application/json")
                .body(JSON.toJSONString(paramMap))
                .execute().body();
        log.info(result);
        return JSONObject.parseObject(result);
    }

    /**
     * 刷新 token
     * @param refresh_token
     * @param id
     * @return
     * @throws IOException
     */
    public static JSONObject refreshToken(String refresh_token, long id, String type) {

        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/auth/access_token/get";
        BigInteger sign = getTokenSign(ShopAuthDTO.getPartnerId(), path,timest,ShopAuthDTO.getTempPartnerKey());
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s", ShopAuthDTO.getPartnerId(),timest, String.format("%032x",sign));
        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("refresh_token",refresh_token);
        paramMap.put("shop".equals(type) ? "shop_id" : "merchant_id", id);
        paramMap.put("partner_id",ShopAuthDTO.getPartnerId());
        String result = HttpRequest.post(tmp_url)
                .header(Header.ACCEPT, "application/json")
                .header(Header.CONTENT_TYPE, "application/json")
                .body(JSON.toJSONString(paramMap))
                .execute().body();
        log.info(result);
        return JSONObject.parseObject(result);
    }

    //main account request for the access token for the first time
    // 获取主账号token
    public static JSONObject getAccountAccessToken(String code, long main_account_id) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/auth/token/get";
        BigInteger sign = getTokenSign(ShopAuthDTO.getPartnerId(), path,timest,ShopAuthDTO.getTempPartnerKey());


        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("code",code);
        paramMap.put("main_account_id",main_account_id);
        paramMap.put("partner_id",ShopAuthDTO.getPartnerId());
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s", ShopAuthDTO.getPartnerId(),timest, String.format("%032x",sign));
        String result = HttpRequest.post(tmp_url)
                .header(Header.ACCEPT, "application/json")
                .header(Header.CONTENT_TYPE, "application/json")
                .body(JSON.toJSONString(paramMap))
                .execute().body();
        log.info(result);
        return JSONObject.parseObject(result);
    }

    public static JSONObject getProducts(String accessToken, long shopId) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_item_list";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
//        String tmp_url = host + path + "?partner_id=" + partner_id + "&timestamp=" + timest + "&access_token=" + accessToken + "&shop_id=" + shopId + "&sign=" + String.format("%032x",sign) + "&page_siz=100&item_status=NORMAL&offset=0&update_time_to="+ timest;
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&page_size=10&item_status=NORMAL&offset=0",
                                                    partner_id, timest, String.format("%032x",sign), accessToken, shopId);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);

        return JSONObject.parseObject(result);
    }

    public static JSONObject getProductInfo(String accessToken, long shopId, long itemId) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_item_base_info";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
//        String tmp_url = host + path + "?partner_id=" + partner_id + "&timestamp=" + timest + "&access_token=" + accessToken + "&shop_id=" + shopId + "&sign=" + String.format("%032x",sign) + "&page_siz=100&item_status=NORMAL&offset=0&update_time_to="+ timest;
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&item_id_list=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, itemId);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }


    public static JSONObject getModelList(String accessToken, long shopId, long itemId) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_model_list";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
//        String tmp_url = host + path + "?partner_id=" + partner_id + "&timestamp=" + timest + "&access_token=" + accessToken + "&shop_id=" + shopId + "&sign=" + String.format("%032x",sign) + "&page_siz=100&item_status=NORMAL&offset=0&update_time_to="+ timest;
        String tmp_url = host + path + String.format("?partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&item_id=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, itemId);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }

    public static JSONObject getAttributes(String accessToken, long shopId, long category_id) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_attributes";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
//        String tmp_url = host + path + "?partner_id=" + partner_id + "&timestamp=" + timest + "&access_token=" + accessToken + "&shop_id=" + shopId + "&sign=" + String.format("%032x",sign) + "&page_siz=100&item_status=NORMAL&offset=0&update_time_to="+ timest;
        String tmp_url = host + path + String.format("?&partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&category_id=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, category_id);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }


    public static JSONObject getCategory(String accessToken, long shopId, long category_id) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_category";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
//        String tmp_url = host + path + "?partner_id=" + partner_id + "&timestamp=" + timest + "&access_token=" + accessToken + "&shop_id=" + shopId + "&sign=" + String.format("%032x",sign) + "&page_siz=100&item_status=NORMAL&offset=0&update_time_to="+ timest;
        String tmp_url = host + path + String.format("?&partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&category_id=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, category_id);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }

    public static JSONObject getOrderList(String accessToken, long shopId) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/order/get_order_list";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
        // "https://partner.shopeemobile.com/api/v2/order/get_order_list?page_size=20&response_optional_fields=order_status&timestamp=timestamp&shop_id=shop_id&order_status=READY_TO_SHIP&partner_id=partner_id&access_token=access_token&cursor=""&time_range_field=create_time&time_from=1607235072&time_to=1608271872&sign=sign"
        String tmp_url = host + path + String.format("?&partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&time_range_field=create_time&time_from=%s&time_to=%s&page_size=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, 1711900800, 1713110400, 20);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }

    public static JSONObject getOrderDetail(String accessToken, long shopId, String orderSnList) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/order/get_order_detail";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
        // "https://partner.shopeemobile.com/api/v2/order/get_order_list?page_size=20&response_optional_fields=order_status&timestamp=timestamp&shop_id=shop_id&order_status=READY_TO_SHIP&partner_id=partner_id&access_token=access_token&cursor=""&time_range_field=create_time&time_from=1607235072&time_to=1608271872&sign=sign"
        String tmp_url = host + path + String.format("?&partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&order_sn_list=%s&&request_order_status_pending=true&response_optional_fields=buyer_user_id,buyer_username,estimated_shipping_fee,recipient_address,actual_shipping_fee,goods_to_declare,note,note_update_time,item_list,pay_time,dropshipper, dropshipper_phone,split_up,buyer_cancel_reason,cancel_by,cancel_reason,actual_shipping_fee_confirmed,buyer_cpf_id,fulfillment_flag,pickup_done_time,package_list,shipping_carrier,payment_method,total_amount,buyer_username,invoice_data,no_plastic_packing,order_chargeable_weight_gram,edt",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, orderSnList);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }

    public static JSONObject getItemPromotion(String accessToken, long shopId, String itemIds) {
        long timest = System.currentTimeMillis() / 1000L;
        String host = ShopAuthDTO.getHost();
        String path = "/api/v2/product/get_item_promotion";
        long partner_id = ShopAuthDTO.getPartnerId();
        String tmp_partner_key = ShopAuthDTO.getTempPartnerKey();
        BigInteger sign = getShopTokenSign(partner_id, path,timest, accessToken, shopId, tmp_partner_key);
        // "https://partner.shopeemobile.com/api/v2/order/get_order_list?page_size=20&response_optional_fields=order_status&timestamp=timestamp&shop_id=shop_id&order_status=READY_TO_SHIP&partner_id=partner_id&access_token=access_token&cursor=""&time_range_field=create_time&time_from=1607235072&time_to=1608271872&sign=sign"
        String tmp_url = host + path + String.format("?&partner_id=%s&timestamp=%s&sign=%s&access_token=%s&shop_id=%s&item_id_list=%s",
                partner_id, timest, String.format("%032x",sign), accessToken, shopId, itemIds);

        String result = HttpUtil.get(tmp_url, CharsetUtil.CHARSET_UTF_8);


        return JSONObject.parseObject(result);
    }
}
