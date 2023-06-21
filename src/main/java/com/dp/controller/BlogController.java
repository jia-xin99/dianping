package com.dp.controller;

import com.dp.dto.Result;
import com.dp.entity.Blog;
import com.dp.service.BlogService;
import com.dp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private BlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 获取登录用户
//        UserDTO user = UserHolder.getUser();
//        blog.setUserId(user.getId());
        // 保存探店博文
//        blogService.saveBlog(blog);
        // 返回id
//        return Result.ok(blog.getId());
        // 改进：发送博客时推送给粉丝
        return blogService.saveBlog(blog);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") String id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 点赞和取消点赞
     *
     * @param id: 笔记id
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 查询该评论点赞前5名
     *
     * @param id: 笔记id
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") String id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 查询其他用户笔记列表
     *
     * @param current: 当前页
     * @param userId:  用户id
     */
    @GetMapping("/of/user")
    public Result queryByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long userId) {
        return blogService.queryByUserId(current, userId);
    }

    /**
     * 查询个人笔记列表
     *
     * @param current: 当前页
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        Long userId = UserHolder.getUser().getId();
        return blogService.queryByUserId(current, userId);
    }

    /**
     * 滚动分页查询关注列表的推送的Blog信息
     * ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]：查找分数在（max,min）范围中偏移量为offset时count数量的博客
     * 参数1：max：当前时间戳（第一页） | 上一次查询的最小时间戳minTime（最后一条的分数）；
     * 参数2：min：0
     * 参数3：offeset：x 在上一次查询结果中，与最小值一样的元素的个数（防止多个博客的时间戳相同）
     * 参数4：count：y 要返回的博客消息数
     * @param max：对应ScrollResult中的minTime，记录上次查询的最后一条博客的时间戳
     * @param offset: 上次最后一条博客在其所在时间戳中的偏移量
     */
    @GetMapping("/of/follow")
    public Result queryFollowBlog(@RequestParam(value = "lastId")Long max,
                                  @RequestParam(value = "offset",defaultValue = "0")Integer offset) {
        return blogService.queryBlogOfFollow(max,offset);
    }
}
