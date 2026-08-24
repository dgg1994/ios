package com.forward.config;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

import reactor.netty.http.HttpProtocol;

/**
 * 让网关（forward-server）同时支持 HTTP/1.1 与 HTTP/2 cleartext(h2c)。
 *
 * 背景：客户端用 HTTP/2 明文先验（连接前导 PRI * HTTP/2.0）直连网关，
 * 而 Spring Boot 2.7 的 server.http2.enabled 只管 TLS 上的 h2，不能靠配置项启用 h2c。
 * 这里通过 Reactor Netty 编程式设置 protocol(H2C, HTTP11)：
 *   - H2C：接受客户端的 h2c 先验（PRI * 前导）
 *   - HTTP11：兼容 HTTP/1.1 客户端（手动 curl/Postman 测试）
 * 网关→上游仍保持 HTTP/1.1（spring.cloud.gateway.httpclient.protocol=HTTP11）。
 */
@Configuration
public class H2cConfig implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        factory.addServerCustomizers(httpServer -> httpServer
                .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(10 * 1024 * 1024)));
    }
}
