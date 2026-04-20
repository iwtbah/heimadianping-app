package com.zwz5.controller;


import com.zwz5.common.result.Result;
import com.zwz5.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 请求关注
     *
     * @param followUserId
     * @param isFollow
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 关注状态
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    // 共同关注
    @GetMapping("/common/{id}")
    public Result ifollowCommons(@PathVariable("id") Long followUserId) {
        return followService.followCommons(followUserId);
    }
}
