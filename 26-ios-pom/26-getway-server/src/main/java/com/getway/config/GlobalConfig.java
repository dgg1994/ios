package com.getway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 对齐 forward-server {@code GlobalConfig}：CORS + 请求日志。
 */
@Configuration
public class GlobalConfig {

    private static final Logger REQ_LOG = LoggerFactory.getLogger("gw.req");

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("*");
        cors.addAllowedHeader("*");
        cors.addAllowedMethod(HttpMethod.GET);
        cors.addAllowedMethod(HttpMethod.POST);
        cors.addAllowedMethod(HttpMethod.HEAD);
        cors.addAllowedMethod(HttpMethod.OPTIONS);
        cors.addAllowedMethod(HttpMethod.PUT);
        cors.addAllowedMethod(HttpMethod.DELETE);
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }

    @Bean
    public GlobalFilter requestLogFilter() {
        return (exchange, chain) -> {
            long start = System.currentTimeMillis();
            String method = exchange.getRequest().getMethodValue();
            String path = exchange.getRequest().getURI().getPath();
            String ip = clientIp(exchange);
            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long cost = System.currentTimeMillis() - start;
                HttpStatus status = exchange.getResponse().getStatusCode();
                String target = resolveTarget(exchange);
                REQ_LOG.info("{} {} target={} ip={} status={} cost={}ms",
                        method, path, target, ip, status, cost);
            }));
        };
    }

    private static String resolveTarget(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || route.getUri() == null) {
            return "-";
        }
        String host = route.getUri().getHost();
        return (host == null || host.isEmpty()) ? route.getUri().toString() : host;
    }

    private static String clientIp(ServerWebExchange exchange) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP"};
        for (String h : headers) {
            String v = exchange.getRequest().getHeaders().getFirst(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        var addr = exchange.getRequest().getRemoteAddress();
        return addr == null || addr.getAddress() == null ? "-" : addr.getAddress().getHostAddress();
    }
}
