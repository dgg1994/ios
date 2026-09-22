package com.forward.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * 网关同时接 HTTP/1.1 与 HTTP/2 cleartext(h2c)。
 * <p>
 * HTTP/2 默认流量窗口是 65535。客户端声明 Content-Length=523456 时，
 * 先发出 65535 字节就会停住等 WINDOW_UPDATE，连接表现为 EOF / 请求进不来。
 * 这里把连接窗口和流窗口都放大，让整段 body 能发完。
 */
@Configuration
public class H2cConfig implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(H2cConfig.class);

    /** 大于样例声明的 523456，也盖住后续更大的 wp/tg 包。 */
    static final int H2_WINDOW = 8 * 1024 * 1024;

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        factory.addServerCustomizers(this::customizeHttpServer);
    }

    private HttpServer customizeHttpServer(HttpServer httpServer) {
        log.info("正常日志:[forward] 启用 H2C+HTTP11，HTTP/2 窗口={}", H2_WINDOW);
        return httpServer
                .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
                .http2Settings(spec -> spec.initialWindowSize(H2_WINDOW))
                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(10 * 1024 * 1024))
                .doOnChannelInit((observer, channel, remoteAddress) -> {
                    if (channel.pipeline().get("wp-tg-short-body") == null) {
                        channel.pipeline().addFirst("wp-tg-short-body", new ShortBodyHttp11Handler());
                    }
                })
                .doOnConnection(conn -> {
                    if (conn.channel().pipeline().get("h2-window") == null) {
                        conn.channel().pipeline().addLast("h2-window", new WidenHttp2WindowHandler());
                    }
                });
    }

    /** 连接级窗口也是 65535，只改 SETTINGS 的流窗口不够。 */
    private static final class WidenHttp2WindowHandler extends ChannelInboundHandlerAdapter {
        private boolean done;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            widen(ctx);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent) {
                widen(ctx);
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            widen(ctx);
            ctx.fireChannelRead(msg);
        }

        private void widen(ChannelHandlerContext ctx) {
            if (done) {
                return;
            }
            io.netty.channel.Channel channel = ctx.channel();
            if (channel.parent() != null) {
                channel = channel.parent();
            }
            Http2ConnectionHandler codec = channel.pipeline().get(Http2ConnectionHandler.class);
            if (codec == null) {
                return;
            }
            try {
                Http2Connection connection = codec.connection();
                Http2LocalFlowController flow = connection.local().flowController();
                Http2Stream stream = connection.connectionStream();
                int current = flow.windowSize(stream);
                int delta = H2_WINDOW - current;
                if (delta > 0) {
                    flow.incrementWindowSize(stream, delta);
                }
                done = true;
                log.info("正常日志:[forward] HTTP/2 连接窗口 {} -> {}", current, Math.max(current, H2_WINDOW));
            } catch (Exception e) {
                log.info("异常日志:[forward] 调整 HTTP/2 窗口失败: {}", e.toString());
            }
        }
    }
}
