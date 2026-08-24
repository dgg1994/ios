package com.forward.config;

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
 * forward-server 全局配置
 * - CorsWebFilter：放行浏览器 CORS 预检（必须，作为对外统一入口）
 * - requestLogFilter：记录 method/path/series/target/ip/status/cost-ms
 *   series 取自请求头 X-Series，缺省记为 legacy（17 及以下）
 *   target 取自匹配到的路由 URI（如 lb://17-report-server → 17-report-server）
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
            String series = exchange.getRequest().getHeaders().getFirst("X-Series");
            if (series == null || series.isEmpty()) {
                series = "legacy";
            }
            String seriesFinal = series;
            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long cost = System.currentTimeMillis() - start;
                HttpStatus status = exchange.getResponse().getStatusCode();
                String target = resolveTarget(exchange);
                REQ_LOG.info("{} {} series={} target={} ip={} status={} cost={}ms",
                        method, path, seriesFinal, target, ip, status, cost);
            }));
        };
    }

    /** 从 Gateway 匹配路由解析下游服务名，如 lb://17-report-server → 17-report-server */
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
