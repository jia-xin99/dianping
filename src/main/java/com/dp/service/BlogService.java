package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.Result;
import com.dp.entity.Blog;

public interface BlogService extends IService<Blog> {
    Result queryHotBlog(Integer current);

    Result queryBlogById(String id);

    Result likeBlog(Long id);

    Result queryBlogLikes(String id);

    Result queryByUserId(Integer current, Long userId);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
