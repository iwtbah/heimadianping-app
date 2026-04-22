package com.zwz5.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.Follow;
import com.zwz5.pojo.entity.User;
import com.zwz5.mapper.FollowMapper;
import com.zwz5.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注某
     *
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        Result validateResult = validateFollowTarget(userId, followUserId, isFollow);
        if (validateResult != null) {
            return validateResult;
        }
        String key = "follows:" + userId;
        // 没关注过则关注
        if (isFollow) {
            if (hasFollowed(userId, followUserId)) {
                return Result.fail("重复关注！");
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            try {
                // 数据库联合唯一索引兜底，避免并发下插入重复关注记录。
                boolean save = save(follow);
                if (save) {
                    // DB 写入成功后再同步 Redis 关注集合。
                    stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                }
            } catch (DuplicateKeyException e) {
                return Result.fail("重复关注！");
            }
        } else {
            // 关注则取消
            if (hasFollowed(userId, followUserId)) {
                boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
                if (remove) {
                    // 把关注用户的id从Redis集合中移除
                    stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
                }
            } else {
                return Result.fail("未关注用户！");
            }
        }
        return Result.ok();
    }

    /**
     * 查询是否关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        boolean isFollow = hasFollowed(userId, followUserId);
        return Result.ok(isFollow);
    }

    /**
     * 共同关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result followCommons(Long followUserId) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        // 2.求交集
        String key2 = "follows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);

        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }

        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> userDTOList = userService.listByIds(ids)
                .stream()
                .map(user -> {
                    UserDTO dto = new UserDTO();
                    BeanUtils.copyProperties(user, dto);
                    return dto;
                }).toList();

        return Result.ok(userDTOList);
    }

    private boolean hasFollowed(Long userId, Long followUserId) {
        return lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId)
                .count() > 0;
    }

    private Result validateFollowTarget(Long userId, Long followUserId, Boolean isFollow) {
        if (followUserId == null) {
            return Result.fail("目标用户不能为空！");
        }
        if (isFollow == null) {
            return Result.fail("关注操作参数不能为空！");
        }
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己！");
        }
        User followUser = userService.getById(followUserId);
        if (followUser == null) {
            return Result.fail("目标用户不存在！");
        }
        return null;
    }
}
