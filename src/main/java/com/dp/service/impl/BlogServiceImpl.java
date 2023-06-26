package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.dto.ScrollResult;
import com.dp.dto.UserDTO;
import com.dp.entity.Blog;
import com.dp.entity.Follow;
import com.dp.entity.User;
import com.dp.mapper.BlogMapper;
import com.dp.service.BlogService;
import com.dp.service.FollowService;
import com.dp.service.UserService;
import com.dp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Var;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.lang.model.element.TypeElement;

import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dp.utils.SystemConstants.MAX_PAGE_SIZE;
import static com.dp.utils.redis.RedisConstants.BLOG_LIKED_KEY;
import static com.dp.utils.redis.RedisConstants.FEED_KEY;

@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogService {

    @Resource
    private UserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = new Page<>(current, MAX_PAGE_SIZE);
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<>();
        // 根据点赞数排序
        queryWrapper.orderByDesc(Blog::getLiked);
        page = this.page(page, queryWrapper);
        List<Blog> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return Result.ok();
        }
        records.forEach(blog -> {
            // 给博客添加对应的用户信息（比如姓名、Icon等）
            this.queryBlogUser(blog);
            // 给博客添加登录用户是否点赞的信息
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(String id) {
        // 1. 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 2. 查询和blog相关的用户
        queryBlogUser(blog);
        // 3. 查询该blog是否被当前用户点赞，添加是否点赞的信息
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    // 给当前用户添加是否点赞该博客的信息
    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否已点赞（未登录用户也是被允许访问首页）
            return;
        }
        Long userId = user.getId();
        // 2. 判断当前用户是否已经点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        // 使用set---改进：zset
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
//        blog.setIsLike(BooleanUtil.isTrue(isMember));
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 判断用户已是否点赞（通过redis的set集合）
        String key = BLOG_LIKED_KEY + id;
        // 如果只做点赞，可以使用set。若对点赞时间进行排序，则需要使用zset
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3. 如果未点赞，则可以点赞
            // 3.1 数据库点赞数+1
            LambdaUpdateWrapper<Blog> queryWrapper = Wrappers.<Blog>lambdaUpdate()
                    .eq(Blog::getId, id)
                    .setSql("liked = liked + 1");
            boolean success = this.update(queryWrapper);
            if (success) {
                // 3.2 保存用户到Redis的set集合中---改进：zset，score用当前时间戳做记录
//                stringRedisTemplate.opsForSet().add(key, userId.toString());
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 如果已点赞，则取消点赞
            // 4.1 数据库点赞数-1
            LambdaUpdateWrapper<Blog> queryWrapper = Wrappers.<Blog>lambdaUpdate()
                    .eq(Blog::getId, id)
                    .setSql("liked = liked - 1");
            boolean success = this.update(queryWrapper);
            if (success) {
                // 4.2 从Redis的set集合中删除用户---改进：zset
//                stringRedisTemplate.opsForSet().remove(key, userId.toString());
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(String id) {
        String key = BLOG_LIKED_KEY + id;
        // 获取最新点赞的5个用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据用户id查询信息
        // 版本1：注意：listByIds类似in(ids)，返回的user信息并不是按照ids的排序而排序
//        List<UserDTO> users = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        // 版本2：需要使用in (ids) ORDER BY FIELD（id,5,3,4,1）---指定输出顺序
        String idStr = StrUtil.join(",", ids);
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
                .in(User::getId, ids)
                .last("ORDER BY FIELD(id," + idStr + ")");
        List<UserDTO> users = userService.list(queryWrapper)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result queryByUserId(Integer current, Long userId) {
        Page<Blog> blogPage = new Page<>(current, MAX_PAGE_SIZE);
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<Blog>()
                .eq(Blog::getUserId, userId)
                .orderByDesc(Blog::getCreateTime);
        blogPage = this.page(blogPage, queryWrapper);
        return Result.ok(blogPage.getRecords());
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 保存探店信息
        blog.setUserId(userId);
        boolean success = this.save(blog);
        if (!success) {
            return Result.fail("发布失败");
        }
        // 3. 查询笔记作者关注者（也可做Redis缓存中）
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowUserId, userId);
        List<Follow> follows = followService.list(queryWrapper);
        Long blogId = blog.getId();
        // 4. 推送笔记id给所有粉丝（可异步消息）
        for (Follow follow : follows) {
            // 4.1 获取粉丝id
            Long followerId = follow.getUserId();
            // 4.2 推送到其的zset中，value为当前博客id，score为当前时间
            String key = FEED_KEY + followerId;
            stringRedisTemplate.opsForZSet().add(key, blogId.toString(), System.currentTimeMillis());
        }
        // 5. 返回id
        return Result.ok(blogId);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱（第一次查询时，max为当前时间戳，偏移量为0，就是第一页数据，不需要特殊处理）
        String key = FEED_KEY + userId;
        // ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4. 解析数据：blogId,minTime,offset --- 该数据返回时已排好序
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 获取分数（时间戳）
            long time = typedTuple.getScore().longValue();
            if (minTime != time) {
                minTime = time;
                os = 1;
            } else {
                os++;
            }
        }
        // 5. 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<Blog>()
                .in(Blog::getId, ids)
                .last("ORDER BY FIELD(id," + idStr + ")");
        List<Blog> blogs = this.list(queryWrapper);
        // 添加博客是否被点赞等额外信息
        for (Blog blog : blogs) {
            // 查询博客相关的用户
            queryBlogUser(blog);
            // 查询博客是否被点赞
            isBlogLiked(blog);
        }
        // 6. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
}

