package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.RedisConstants;
import com.zwz5.constants.SystemConstants;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.User;
import com.zwz5.mapper.UserMapper;
import com.zwz5.service.IUserService;
import com.zwz5.common.utils.RandomUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public User createUserWithPhone(String phone) {
        User user = User.builder().phone(phone).nickName("user_" + RandomUtils.generateRandomString(10)).build();
        save(user);
        return user;
    }

    /**
     * 用户签到
     *
     * @return
     */
    @Override
    public Result sign() {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 获取当前日期的月份
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取当前为月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入Redis中的BitMap
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1))) {
            return Result.ok("用户已经签到");
        }
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 获取当前日期的月份
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取当前为月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截止到今天的签到记录，返回十进制数字
        // Redis 的 位图（bitmap）按位读取操作：ITFIELD sign:1010:202605 GET u14 0
        // u14 unsigned无符号读取14为，将今日作为参数，实现读取截止到今天的签到数，0为偏移量
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        // 没有任何签到结果
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 循环遍历
        int count = 0;
        for (int i = 0; i < dayOfMonth; i++) {
            // 判断最后一位，如果签到则累加，未位签到则结束
            if ((num & 1) != 0) {
                count++;
            } else {
                break;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
