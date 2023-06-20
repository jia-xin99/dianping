package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.dto.LoginFormDTO;
import com.dp.dto.Result;
import com.dp.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

public interface UserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    User getOneByPhone(String phone);

    Result logout(HttpServletRequest request);

//    Result sign();
//
//    Result signCount();
}
