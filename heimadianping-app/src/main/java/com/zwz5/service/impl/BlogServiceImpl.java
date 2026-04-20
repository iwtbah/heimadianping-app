package com.zwz5.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.SystemConstants;
import com.zwz5.mapper.BlogMapper;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.Blog;
import com.zwz5.pojo.entity.User;
import com.zwz5.service.IBlogService;
import com.zwz5.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.zwz5.constants.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询博客详情，并补充作者信息
     *
     * @param id 博客id
     * @return 博客详情
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        fillBlogUser(blog);
        return Result.ok(blog);
    }

    /**
     * 分页查询热门博客，并补充作者信息
     *
     * @param current 页码
     * @return 热门博客列表
     */
    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::fillBlogUser);
        return Result.ok(records);
    }

    /**
     * 查询博客点赞用户列表
     *
     * @param id 博客id。
     * @return 点赞用户列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1.查询TOP5点赞的人，按时间排序
        String key = BLOG_LIKED_KEY + id;
        Set<String> topRange = stringRedisTemplate.opsForZSet().range(key, 0, SystemConstants.BOLG_LIKES_MAX_SIZE);
        if (topRange == null || topRange.isEmpty()) {
            // 1.1 无人点赞则返回空集合
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = topRange.stream().map(Long::valueOf).toList();
        // 2.查询点赞的用户相关信息
        List<UserDTO> userDTOList = userService.listByIds(userIds)
                .stream()
                .map(user -> {
                    UserDTO dto = new UserDTO();
                    BeanUtils.copyProperties(user, dto);
                    return dto;
                })
                .toList();
        return Result.ok(userDTOList);
    }

    /**
     * 点赞博客
     *
     * @param id 博客id
     * @return 操作结果
     */
    @Override
    public Result likeBlog(Long id) {
        // 点赞方案
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 为空则说明没有登录，直接返回
            return Result.fail("未登录");
        }
        // 2.判断用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        if (score == null) {
            // 2.1 如果没有点赞缓存，则可以点赞，并在数据库中自增
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                // 将点赞时间作为排序score
                stringRedisTemplate.opsForZSet().add(key, user.getId().toString(), System.currentTimeMillis());
            }
        } else {
            // 2.2 如果已经被点赞过，则取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // 删除缓存
                stringRedisTemplate.opsForZSet().remove(key, user.getId().toString());
            }

        }
        return Result.ok();
    }

    /**
     * 补充博客作者相关信息，和是否被当前用户点赞信息
     *
     * @param blog 博客对象
     */
    private void fillBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        UserDTO me = UserHolder.getUser();
        // 是否被点赞
        if (me != null) {
            String key = BLOG_LIKED_KEY + blog.getId();
            Double score = stringRedisTemplate.opsForZSet().score(key, me.getId().toString());
            blog.setIsLike(score != null);
        }
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
}
