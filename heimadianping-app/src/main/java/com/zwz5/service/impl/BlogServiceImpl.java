package com.zwz5.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zwz5.common.result.Result;
import com.zwz5.common.result.ScrollResult;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.SystemConstants;
import com.zwz5.mapper.BlogMapper;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.Blog;
import com.zwz5.pojo.entity.Follow;
import com.zwz5.pojo.entity.User;
import com.zwz5.service.IBlogService;
import com.zwz5.service.IFollowService;
import com.zwz5.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.zwz5.constants.RedisConstants.BLOG_LIKED_KEY;
import static com.zwz5.constants.RedisConstants.FEED_KEY;

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
    private IFollowService followService;

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

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean save = save(blog);
        if (!save) {
            return Result.fail("新增笔记失败!");
        }
        // 查询该用户的粉丝
        List<Follow> follows = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, userId)
                .list();
        // 推送该笔记到Redis
        for (Follow follow : follows) {
            String key = FEED_KEY + follow.getUserId().toString();
            // 推送
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 查询当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 查询Redis中的收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 判断是否为空
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析数据，根据时间戳分页
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        int offset_next = 0;
        Long minTime = max;

        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            Long blog_id = Long.valueOf(Objects.requireNonNull(typedTuple.getValue()));
            blogIds.add(blog_id);
            // 更新时间戳
            long score = Objects.requireNonNull(typedTuple.getScore()).longValue();
            // 如果该blog更早，则更新时间戳
            if (score < minTime) {
                minTime = score;
                offset_next = 1;
            } else if (score == minTime) {
                // 如果重复，则记录数量，用于跳过重复项
                offset_next++;
            }
        }
        // 5.根据id查询blog
        String idStr = blogIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 获取Blog详细
        // 获取Blog有关的用户
        // 获取Blog是否被点赞
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户和是否被点赞
            fillBlogUser(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(offset_next);
        r.setMinTime(minTime);
        return Result.ok(r);

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
