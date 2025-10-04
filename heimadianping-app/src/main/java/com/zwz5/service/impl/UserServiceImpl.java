package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.pojo.entity.User;
import com.zwz5.mapper.UserMapper;
import com.zwz5.service.IUserService;
import com.zwz5.utils.RandomUtils;
import org.springframework.stereotype.Service;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public User createUserWithPhone(String phone) {
        User user = User.builder()
                .phone(phone)
                .nickName("user_" + RandomUtils.generateRandomString(10))
                .build();
        save(user);
        return user;
    }
}
