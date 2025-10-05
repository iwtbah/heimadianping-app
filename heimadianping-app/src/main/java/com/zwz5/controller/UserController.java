package com.zwz5.controller;

import com.zwz5.pojo.dto.LoginFormDTO;
import com.zwz5.common.Result;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.User;
import com.zwz5.pojo.entity.UserInfo;
import com.zwz5.service.IUserInfoService;
import com.zwz5.service.IUserService;
import com.zwz5.utils.RandomUtils;
import com.zwz5.constants.RedisConstants;
import com.zwz5.utils.RegexUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 生成验证码
        String code = RandomUtils.generateRandomString(6);
        session.setAttribute(phone, code);

        // 存入Redis，并设置过期时间
        String redisKey = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(redisKey, code ,2, TimeUnit.MINUTES);
        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){

        // 校验手机号
        String phone = loginForm.getPhone();
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 校验验证码
        String code = loginForm.getCode();
        if (code == null) {
            return Result.fail("验证码为空！");
        }
        // 从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !code.equals(cacheCode)) {
            return Result.fail("验证码错误！");
        }
        // 查找用户，如果不存在则创建
        User user = userService.query().eq("phone", phone).one();
        if (user == null) {
            //不存在，则创建
            user =  userService.createUserWithPhone(phone);
        }
        // 保存用户信息到redis中
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        // 将User对象转化为HashMap
        Map<String, String> userMap = new HashMap<>();
        BeanMap.create(userDTO).forEach((k, v) -> userMap.put(k.toString(), v == null ? "" : v.toString()));
        String redisToken = RedisConstants.LOGIN_USER_KEY + token;
        // 存入Redis，并设置过期时间
        stringRedisTemplate.opsForHash().putAll(redisToken, userMap);
        stringRedisTemplate.expire(redisToken,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        return Result.ok(token);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        return Result.fail("功能未完成");
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
