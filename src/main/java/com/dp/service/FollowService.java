package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.Result;
import com.dp.entity.Follow;

public interface FollowService extends IService<Follow> {
    Result follow(Long followId, Boolean isFollow);

    Result isFollow(Long followId);

    Result commonFollow(Long otherUserId);
}
