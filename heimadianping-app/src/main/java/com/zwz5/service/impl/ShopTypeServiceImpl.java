package com.zwz5.service.impl;

import com.zwz5.pojo.entity.ShopType;
import com.zwz5.mapper.ShopTypeMapper;
import com.zwz5.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.utils.JsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.zwz5.constants.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.zwz5.constants.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 商铺类型服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JsonUtils jsonUtils;

    @Override
    public List<ShopType> queryList() {
        // 查询缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 命中缓存，反序列化为对象返回
        if (StringUtils.hasLength(shopTypeJson)) {
            return jsonUtils.jsonToList(shopTypeJson, ShopType.class);
        }
        // 未命中则查询数据库
        List<ShopType> shopTypes = list();
        if (shopTypes == null || shopTypes.isEmpty()) {
            return null;
        }
        // 写入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, jsonUtils.beanToJson(shopTypes), CACHE_SHOP_TYPE_TTL, TimeUnit.HOURS);
        return shopTypes;
    }
}
