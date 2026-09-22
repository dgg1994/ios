package com.send.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.send.util.ClientIpUtil;
import com.send.util.IpRateLimiter;

/**
 * 客户端上报限流（对齐 yml {@code v26.rate-limit-per-minute}）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private V26SendProperties props;

    @Autowired
    private IpRateLimiter ipRateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                int limit = props.getRateLimitPerMinute();
                if (limit <= 0) {
                    return true;
                }
                String ip = ClientIpUtil.resolve(request);
                if (!ipRateLimiter.tryAcquire(ip, limit)) {
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":429,\"message\":\"rate limit exceeded\",\"data\":null}");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**", "/api/v2/**", "/api/handshake.php", "/api/session.php", "/api/upload.php")
                .excludePathPatterns("/api/v1/system/ready", "/api/v2/system/ready");
    }
}
