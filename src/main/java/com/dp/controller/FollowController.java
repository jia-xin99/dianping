package com.dp.controller;

import com.dp.dto.Result;
import com.dp.service.FollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private FollowService followService;

    /**
     * 关注和取关：根据isFollow进行判断
     *
     * @param followId: 关注的用户id
     * @param isFollow: 是否关注
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followId, isFollow);
    }

    /**
     * 判断是否关注该用户
     *
     * @param followId: 关注的用户id
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followId) {
        return followService.isFollow(followId);
    }

    /**
     * 共同关注
     *
     * @param otherUserId: 其他用户id
     */
    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long otherUserId) {
        return followService.commonFollow(otherUserId);
    }
}

