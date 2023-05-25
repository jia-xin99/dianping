package com.dp.config.interceptor;

import com.dp.dto.UserDTO;
import com.dp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;


/**
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 在Controller处理之前进行执行，根据ThreadLocal进行校验是否登录
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（TheadLocal中是否有用户）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，拦截，返回401
            response.setStatus(401);
            return false;
        }
        return true;
    }

    /**
     * 使用Session
     */
    public boolean preHandle1(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取session
        HttpSession session = request.getSession();
        // 2. 获取session中的用户
        Object user = session.getAttribute("user");
        // 3. 判断用户是否存在
        if (user == null) {
            // 4. 不存在，拦截，返回401
            response.setStatus(401);
            return false;
        }
        // 5. 存在，保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        // 6. 放行
        return true;
    }
}

