package com.example.sqlquery.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    public WebMvcConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor=jwtInterceptor;
    }

    /**
     * 拦截规则。
     *
     * <p>白名单里放的是"调用时<b>还没有</b>一个可用登录态"的三个接口：
     * 注册、登录，以及登出。
     *
     * <p>{@code /api/auth/logout} 放在白名单里是刻意的：token 已经过期的用户
     * 也应该点得动"退出"（否则前端会卡在"看起来还登录着"的状态）。
     * 代价是这个接口拿到的 token 完全没验签 —— <b>所以验签的责任下移到了
     * {@code AuthController.logout} 内部</b>，它必须先验签、验过才写黑名单。
     * 这两处是一个整体，改任意一边都要同时看另一边。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/logout"
                );
    }
}
