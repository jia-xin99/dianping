package com.dp.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.entity.UserInfo;
import com.dp.mapper.UserInfoMapper;
import com.dp.service.UserInfoService;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

}
