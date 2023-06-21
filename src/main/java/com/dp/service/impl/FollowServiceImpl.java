package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.Result;
import com.dp.dto.UserDTO;
import com.dp.entity.Follow;
import com.dp.mapper.FollowMapper;
import com.dp.service.FollowService;
import com.dp.service.UserService;
import com.dp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dp.utils.redis.RedisConstants.FOLLOW_KEY;

@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    @Resource
    private UserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        // 2. 判断是取关还是关注
        if (isFollow) {
            // 2.1 关注，新增数据
            // 先判断数据库是否有数据，避免重复关注
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followId);
            int count = this.count(queryWrapper);
            if (count == 0) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followId);
                boolean success = save(follow);
                if (!success) {
                    return Result.fail("关注失败");
                }
                // 将关注的用户id插入插入到Redis中的关注Set中，便于后期进行共同关注的查询
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        } else {
            // 2.2 取关，从关注表中删除记录
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followId);
            boolean success = this.remove(queryWrapper);
            if (!success) {
                return Result.fail("取关失败");
            }
            // 将关注的用户从关注set中删除
            stringRedisTemplate.opsForSet().remove(key, followId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.1 判断是否关注（数据库层面）
//        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followId);
//        int count = this.count(queryWrapper);
        // 3.1 返回结果
//        return Result.ok(count > 0);
        // 2.2 判断是否关注（Redis中Set）
        String key = FOLLOW_KEY + userId;
        // 3.2 返回结果
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, followId.toString());
        return Result.ok(BooleanUtil.isTrue(isMember));
    }

    @Override
    public Result commonFollow(Long otherUserId) {
        // 当前用户
        Long userId = UserHolder.getUser().getId();
        String userKey = FOLLOW_KEY + userId;
        String otherUserKey = FOLLOW_KEY + otherUserId;
        // 获取共同关注集合，求2个Set的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(userKey, otherUserKey);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4. 查询用户
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}

