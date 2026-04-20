package com.zwz5.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.Follow;
import com.zwz5.mapper.FollowMapper;
import com.zwz5.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
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
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = "follows:" + userId;
        if (isFollow) {
            // 没关注过则关注
            // 检查是否关注 TODO
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean save = save(follow);
            if (save) {
                // 保存的redis中
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            // 关注则取消
            // 检查是否关注 TODO
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (remove) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     *  查询是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 共同关注
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
}
