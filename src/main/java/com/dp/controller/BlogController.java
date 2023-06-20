package com.dp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dp.dto.Result;
import com.dp.dto.UserDTO;
import com.dp.entity.Blog;
import com.dp.service.BlogService;
import com.dp.service.UserService;
import com.dp.utils.UserHolder;
import io.reactivex.internal.util.BlockingHelper;
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
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        blogService.save(blog);
        // 返回id
        return Result.ok(blog.getId());
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
}
