package com.dp.config;

import com.dp.config.interceptor.LoginInterceptor;
import com.dp.config.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 添加登录拦截器、token刷新拦截器，放行登录、验证码等接口
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/voucher/**",
                        "/upload/**",
                        "/shop/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/user/login",
                        "/user/code"
                ).order(1);
        // token刷新拦截器，优先级比登录拦截器高
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
