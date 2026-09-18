package com.getway.config;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

import reactor.netty.http.HttpProtocol;

/**
 * 对齐 forward-server：网关同时支持 HTTP/1.1 与 h2c。
 * 上游仍走 HTTP/1.1（application.yml {@code httpclient.protocol: HTTP11}）。
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
