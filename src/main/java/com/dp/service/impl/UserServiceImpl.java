package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.dto.LoginFormDTO;
import com.dp.dto.Result;
import com.dp.dto.UserDTO;
import com.dp.entity.User;
import com.dp.mapper.UserMapper;
import com.dp.service.UserService;
import com.dp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.redis.RedisConstants.*;
import static com.dp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 使用redis进行保存验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 1.1 如果不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }
        // 补充：查看redis中是否有验证码
        String s = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (StrUtil.isNotBlank(s)) {
            return Result.ok();
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到redis并设置过期时间 -- set key value
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 5. 返回ok
        return Result.ok();
    }

    /**
     * 使用session进行保存验证码
     */
    public Result sendCode1(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 1.1 如果不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3.0 保存验证码到session
        session.setAttribute("code", code);
        // 4. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 5. 返回ok
        return Result.ok();
    }

    /**
     * 使用redis进行登录校验
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 2. 校验验证码：从Redis中获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码有误");
        }
        // 补充：顺便把验证码删除了
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        // 3. 根据手机号查询用户，判断用户是否存在
        User user = this.getOneByPhone(loginForm.getPhone());
        if (user == null) {
            // 不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 4. 保存用户信息到redis中
        // 4.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 4.2 将user对象转为Hash存储，注意key_value为String，否则报错
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName, fileValue) -> fileValue.toString()));
        // 4.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 4.4 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 5. 返回token
        return Result.ok(token);
    }

    /**
     * 使用session进行登录校验
     */
    public Result login1(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 2. 校验验证码：从Session中获取验证码并校验
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码有误");
        }
        // 3. 根据手机号查询用户，判断用户是否存在
        User user = this.getOneByPhone(loginForm.getPhone());
        if (user == null) {
            // 不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 4. 保存用户信息到session中 --使用UserDTO封装用户数据，隐藏用户敏感信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", userDTO);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        this.save(user);
        return user;
    }

    @Override
    public User getOneByPhone(String phone) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        return this.getOne(queryWrapper);
    }

    @Override
    public Result logout(HttpServletRequest servletRequest) {
        // 删除redis中token
        String token = servletRequest.getHeader("authorization");
        String tokenKey = LOGIN_USER_KEY + token;
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(tokenKey);
        }
        return Result.ok("退出成功");
    }
}

