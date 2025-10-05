package com.zwz5.service.impl;

import com.zwz5.exception.NullException;
import com.zwz5.pojo.entity.Shop;
import com.zwz5.mapper.ShopMapper;
import com.zwz5.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.utils.JsonUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

import static com.zwz5.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JsonUtils jsonUtils;

    @Override
    public Shop queryById(Long id) {
        // 查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 命中缓存，反序列化为对象返回
        if (StringUtils.hasLength(shopJson)) {
           return jsonUtils.jsonToBean(shopJson, Shop.class);
        }
        // 未命中则查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            throw new NullException("未查询到商品");
        }
        // 写入缓存
        stringRedisTemplate.opsForValue().set(key, jsonUtils.beanToJson(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public void update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            throw new NullException("商品id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除对应缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
    }
}
