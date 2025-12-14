package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.cache.RedissonCacheClient;
import com.zwz5.common.result.Result;
import com.zwz5.constants.RedisConstants;
import com.zwz5.pojo.entity.Voucher;
import com.zwz5.mapper.VoucherMapper;
import com.zwz5.pojo.entity.SeckillVoucher;
import com.zwz5.service.ISeckillVoucherService;
import com.zwz5.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonCacheClient cacheClient;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 组装缓存前缀，使用店铺 id 拼接形成缓存 key
        Voucher[] vouchers = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_VOUCHER_KEY + ":",
                shopId,
                Voucher[].class,
                sid -> {
                    // 回源数据库查询当前店铺的优惠券列表
                    List<Voucher> list = getBaseMapper().queryVoucherOfShop(sid);
                    // 查询结果为空时返回 null，由缓存组件写入空值占位
                    return list == null ? null : list.toArray(new Voucher[0]);
                },
                RedisConstants.CACHE_SHOP_VOUCHER_TTL,
                TimeUnit.MINUTES
        );
        // 从Redis中获取实时库存，封装到缓存中
        if (vouchers != null && vouchers.length > 0) {
            for (Voucher voucher : vouchers) {
                // 只处理秒杀券
                if (voucher.getType() != null && voucher.getType() == 1) {
                    String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getId();
                    String stockStr = stringRedisTemplate.opsForValue().get(stockKey);

                    if (stockStr != null) {
                        try {
                            voucher.setStock(Integer.valueOf(stockStr));
                        } catch (NumberFormatException e) {
                            // Redis 中库存异常，兜底使用 DB 库存 或旧数据
                        }
                    }
                }
            }
        }
        return Result.ok(vouchers == null ? List.of() : Arrays.asList(vouchers));
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 1. 参数基本校验（可按需加上）
        if (voucher == null) {
            throw new IllegalArgumentException("voucher 不能为空");
        }
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("秒杀库存必须大于 0");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            throw new IllegalArgumentException("秒杀开始时间和结束时间不能为空");
        }
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存库存信息到Redis中
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + seckillVoucher.getVoucherId();
        stringRedisTemplate.opsForValue().set(stockKey, seckillVoucher.getStock().toString());
        // 保存优惠卷活动时间
        String infoKey = RedisConstants.SECKILL_INFO_KEY + seckillVoucher.getVoucherId();
        long beginMillis = voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        stringRedisTemplate.opsForHash().put(infoKey, "beginTime", String.valueOf(beginMillis));
        stringRedisTemplate.opsForHash().put(infoKey, "endTime", String.valueOf(endMillis));

        // 删除对应店铺的优惠卷缓存
        Long shopId = voucher.getShopId();
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_VOUCHER_KEY + ":" + shopId);

        // 为库存 key 设置过期时间：在秒杀结束时间后自动过期
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = voucher.getEndTime();

        if (endTime.isAfter(now)) {
            long ttlSeconds = java.time.Duration.between(now, endTime).getSeconds();
            // Redis 里设置过期时间（库存 & 活动信息）
            stringRedisTemplate.expire(stockKey, ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
            stringRedisTemplate.expire(infoKey, ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            // 理论上不会走到这里：如果 endTime 已经过期，可以选择直接删除 key
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(infoKey);
        }
    }
}
