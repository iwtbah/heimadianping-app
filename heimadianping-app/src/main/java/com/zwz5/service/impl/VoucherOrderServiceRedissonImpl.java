package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.lock.Ilock;
import com.zwz5.common.lock.SimpleRedisLock;
import com.zwz5.common.redis.RedisIdWorker;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.RedisConstants;
import com.zwz5.mapper.VoucherOrderMapper;
import com.zwz5.pojo.entity.SeckillVoucher;
import com.zwz5.pojo.entity.VoucherOrder;
import com.zwz5.service.ISeckillVoucherService;
import com.zwz5.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 优惠卷秒杀 方案二
 * 基于Redisson实现的分布式锁
 */
@Service("voucherOrderServiceRedisson")
public class VoucherOrderServiceRedissonImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 秒杀优惠卷抢购实现
     *
     * @param voucherId 优惠卷id
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.判断优惠卷是否存在
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("没有优惠卷");
        }
        // 2.判断优惠卷是否开始或结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("抢购还未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("抢购已经结束！");
        }
        // 3.判断优惠卷库存是否足够
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        // 使用Redisson方案
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        try {
            // arg1:锁等待重试实际 arg2:锁自动释放时间，watchdog自动续期
            boolean success = lock.tryLock();
            if (!success) {
                return Result.fail("不允许重复下单！");
            }
            // 拿到锁后，通过代理对象完成订单检测，库存扣减，下单
            IVoucherOrderService voucherOrderServiceProxy = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderServiceProxy.createVoucherOrder(userId, voucherId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long userId, Long voucherId) {
        // 4.判断用户是否购买
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买！");
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //.eq("stock", voucher.getStock())
                .gt("stock", 0)   // 关键：stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 6.增加订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
