package com.zwz5.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zwz5.pojo.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    User createUserWithPhone(String phone);
}
