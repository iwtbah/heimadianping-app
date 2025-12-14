package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.lock.Ilock;
import com.zwz5.common.lock.SimpleRedisLock;
import com.zwz5.common.redis.RedisIdWorker;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.RedisConstants;
import com.zwz5.constants.SeckillResultCodeConstants;
import com.zwz5.mapper.VoucherOrderMapper;
import com.zwz5.pojo.entity.SeckillVoucher;
import com.zwz5.pojo.entity.VoucherOrder;
import com.zwz5.service.ISeckillVoucherService;
import com.zwz5.service.IVoucherOrderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * 优惠卷秒杀 方案二
 * 基于Redisson分布式锁 + 异步阻塞队列实现
 */
@Slf4j
@Service("voucherOrderServiceRedisson")
public class VoucherOrderServiceRedissonImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 避免循环依赖，事务失效
    private volatile IVoucherOrderService proxy;

    // 阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 避免使用公共 ForkJoinPool，异步任务有自己可观测、可限流的线程池
    @Resource
    private Executor cacheOpsExecutor;

    private final DefaultRedisScript<Long> SECKILL_SCRIPT;

    {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill_script.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        CompletableFuture.runAsync(() -> {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }, cacheOpsExecutor);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Long orderId = voucherOrder.getId();
        // 使用Redisson方案
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean locked = false;
        try {
            // arg1:锁等待重试实际 arg2:锁自动释放时间，watchdog自动续期
            locked = lock.tryLock();
            if (!locked) {
                log.info("user:{} voucher:{} : 不允许重复下单！", userId, voucherOrder);
                return;
            }
            // 拿到锁后完成订单检测，库存扣减，下单
            proxy.createVoucherOrder(userId, voucherId, orderId);
            //return 订单id
        } catch (Exception e) {
            log.error("user:{} voucher:{} : 处理订单异常！", userId, voucherOrder);
            throw new RuntimeException(e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 秒杀优惠卷抢购实现
     *
     * @param voucherId 优惠卷id
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 生成订单id
        Long orderId = redisIdWorker.nextId("order");
        // 用LUA对库存校验
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        return switch (result.intValue()) {
            case SeckillResultCodeConstants.NOT_STARTED -> Result.fail("抢购还未开始！");
            case SeckillResultCodeConstants.ENDED -> Result.fail("抢购已经结束！");
            case SeckillResultCodeConstants.NO_STOCK -> Result.fail("库存不足");
            case SeckillResultCodeConstants.DUPLICATE -> Result.fail("不能重复下单");
            default -> {
                // 保存订单到阻塞队列，
                // TODO 用消息队列优化
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setVoucherId(voucherId);
                voucherOrder.setUserId(userId);
                voucherOrder.setId(orderId);
                voucherOrder.setStatus(1);
                proxy = (IVoucherOrderService) AopContext.currentProxy();
                // 当队列满了，消费速度跟不上，则等待重试，避免丢失订单信息
                boolean offered = orderTasks.offer(voucherOrder);
                if (!offered) {
                    yield Result.fail("系统繁忙，请稍后再试");
                }
                yield Result.ok(orderId);
            }
        };

    }

    @Transactional
    public Result createVoucherOrder(Long userId, Long voucherId, Long orderId) {
        // 4.判断用户是否购买
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买！");
        }
        // 5.扣减库存 乐观锁
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
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        log.info("user:{} voucher:{} : 下单成功！", userId, voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
