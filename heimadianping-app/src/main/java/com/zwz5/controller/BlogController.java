package com.zwz5.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zwz5.common.result.Result;
import com.zwz5.common.utils.UserHolder;
import com.zwz5.constants.SystemConstants;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.Blog;
import com.zwz5.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 新增探店博客
     *
     * @param blog 博客内容
     * @return 新增博客id
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞博客
     *
     * @param id 博客id
     * @return 操作结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable Long id) {
        // 点赞该博客
        return blogService.likeBlog(id);
    }

    /**
     * 查询博客点赞用户
     *
     * @param id 博客id
     * @return 点赞用户列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        // 查询该blog下所有点赞的人
        return blogService.queryBlogLikes(id);
    }

    /**
     * 根据id查询博客详情
     *
     * @param id 博客id
     * @return 博客详情
     */
    @RequestMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 根据用户id查询相关的博客
     * @param current
     * @param id
     * @return
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页查询当前用户发布的博客
     *
     * @param current 页码
     * @return 当前用户博客列表
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * Feed流查询关注用户的博客
     * @param max
     * @param offset
     * @return
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }

    /**
     * 分页查询热门博客
     *
     * @param current 页码
     * @return 热门博客列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

}
