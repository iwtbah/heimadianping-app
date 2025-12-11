package com.zwz5.controller;


import com.zwz5.common.result.Result;
import com.zwz5.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    @Qualifier("voucherOrderServiceRedisson")
    private IVoucherOrderService voucherOrderServiceRedisson;

    @Resource
    @Qualifier("voucherOrderServiceSetNxLua")
    private IVoucherOrderService voucherOrderServiceNxLua;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderServiceNxLua.seckillVoucher(voucherId);
    }

}
