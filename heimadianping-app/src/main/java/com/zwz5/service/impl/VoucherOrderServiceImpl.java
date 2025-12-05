package com.zwz5.service.impl;

import com.zwz5.common.redis.RedisIdWorker;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.pojo.entity.SeckillVoucher;
import com.zwz5.pojo.entity.VoucherOrder;
import com.zwz5.mapper.VoucherOrderMapper;
import com.zwz5.service.ISeckillVoucherService;
import com.zwz5.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀优惠卷抢购实现
     *
     * @param voucherId 优惠卷id
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {

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
        // 4.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //.eq("stock", voucher.getStock())
                .gt("stock", 0)   // 关键：stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 5.增加订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setVoucherId(voucherId);
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
