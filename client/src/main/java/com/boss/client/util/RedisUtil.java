package com.boss.client.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.boss.client.service.impl.RedisServiceImpl;
import lombok.extern.slf4j.Slf4j;
import me.codeleep.jsondiff.DefaultJsonDifference;
import me.codeleep.jsondiff.common.model.JsonCompareResult;
import me.codeleep.jsondiff.core.config.JsonComparedOption;
import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * @Description
 * @Author 罗宇航
 * @Date 2024/6/22
 */
@Slf4j
public class RedisUtil {

    public static <T> String judgeRedis(RedisServiceImpl redisService, String redisKey, List<T> list, T t, Class<T> clazz) {
        // 检测入库
        // 将新旧数据全部数据缓存进入 redis
        // 新数据与旧数据进行比较：时间戳
        // key：product:产品id:时间戳
        // value：数据 json 格式化
        // 全量检查！！！！！！
        // 新增：将数据存入新增集合，存入 redis 和 mysql
        // 更新：将更新数据存入更新集合，更新 reids 和 mysql 中的数据
        // 删除：指示标记该条数据被删除！！！不是物理删除，存入删除集合，并在更新 redis 和 mysql 中的数据
        String result = "";

        String newJsonStr = JSON.toJSONString(t, SerializerFeature.WriteMapNullValue);

        // 使用SETNX操作，如果key不存在则设置值，否则返回false
        Boolean flag = redisService.setnx(redisKey, newJsonStr);
        if (flag != null && flag) {
            // 新增或更新成功，无需进一步处理
            list.add(t);
            return result;
        }

        // 获取旧的JSON字符串
        Object redisResult = redisService.get(redisKey);

        JSONObject oldObject = JSON.parseObject(redisResult.toString());
        long id = oldObject.getLong("id");
        oldObject.remove("id");
        String oldJsonStr = oldObject.toJSONString();

        JSONObject newObject = JSON.parseObject(newJsonStr);
        newObject.remove("id");
        newJsonStr = newObject.toJSONString();
        if (ObjectUtils.equals(newJsonStr, oldJsonStr)) {
            // 数据未变化，无需处理
            return result;
        }

        // 进行JSON比较，这里简化为直接字符串比较
        String lockKey = redisKey + "_lock";
        if (redisService.setnx(lockKey, 1)) {
            try {
                JsonComparedOption jsonComparedOption = new JsonComparedOption().setIgnoreOrder(true);
                JsonCompareResult jsonCompareResult = new DefaultJsonDifference()
                        .option(jsonComparedOption)
                        .detectDiff(newJsonStr, oldJsonStr);
                if (!jsonCompareResult.isMatch()) {

                    result = JSON.toJSONString(jsonCompareResult);

                    log.info("newJsonStr====>"+newJsonStr);
                    log.info("oldJsonStr====>"+oldJsonStr);

                    // 更新Redis
                    newObject.put("id", id);
                    newJsonStr = newObject.toJSONString();
                    redisService.set(redisKey, newJsonStr);
                    list.add(JSON.parseObject(newJsonStr, clazz));
                }
            } finally {
                redisService.del(lockKey);
            }
        }
        return result;
    }
}