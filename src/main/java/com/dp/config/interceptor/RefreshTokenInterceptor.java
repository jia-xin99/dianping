package com.dp.config.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dp.dto.UserDTO;
import com.dp.utils.UserHolder;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.dp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 刷新token拦截器
 * 拦截一切请求，优先级最高
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    // 注意：不可注入。原因：该类是需要new的手动加入到拦截器中
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 在Controller处理之前进行执行，使用Redis
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 从请求头中获得token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // 不存在，放行，让登录拦截器处理
            return true;
        }
        String tokenKey = LOGIN_USER_KEY + token;
        // 2. 查询redis中是否存在token
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        // 3. 判断user是否存在
        if (userMap.isEmpty()) {
            // 放行，让登录拦截器处理
            return true;
        }
        // 4. 将user转成UserDTO
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 5. 将用户信息保存在ThreadLocal中
        UserHolder.saveUser(user);
        // 6. 刷新token时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 7. 放行
        return true;
    }

    /**
     * DispatcherServlet进行视图的渲染之后，多用于清理资源
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，防止内存泄露
        UserHolder.removeUser();
    }

}

