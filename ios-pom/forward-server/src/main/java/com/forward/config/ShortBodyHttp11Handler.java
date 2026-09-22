package com.forward.config;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 客户端会带一个大于真实 body 的 Content-Length（样例 523456，实际 65535）。
 * 标准 HTTP 解码会一直读到声明长度，连接不关就永远等下去。
 * 对 wp/tg：body 停住约 0.8 秒后，把 Content-Length 改成实际字节数再交给后面的解码器。
 */
public final class ShortBodyHttp11Handler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ShortBodyHttp11Handler.class);

    private static final long IDLE_MS = 800;
    private static final int MAX_HOLD = 16 * 1024 * 1024;

    private ByteBuf pending;
    private ScheduledFuture<?> idle;
    private boolean passthrough;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (passthrough || !(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (pending == null) {
            pending = ctx.alloc().buffer(in.readableBytes());
        }
        pending.writeBytes(in);
        in.release();
        if (pending.readableBytes() > MAX_HOLD) {
            flushPassthrough(ctx);
            return;
        }
        boolean holding = inspect(ctx, false);
        if (holding) {
            ctx.read();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelIdle();
        if (!passthrough && pending != null && pending.isReadable()) {
            inspect(ctx, true);
            if (!passthrough && pending != null && pending.isReadable()) {
                flushRewritten(ctx, -1, -1);
            }
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelIdle();
        if (pending != null) {
            pending.release();
            pending = null;
        }
    }

    private boolean inspect(ChannelHandlerContext ctx, boolean force) {
        ByteBuf buf = pending;
        if (buf == null || !buf.isReadable()) {
            return false;
        }
        if (startsWithHttp2Preface(buf)) {
            flushPassthrough(ctx);
            return false;
        }
        int headerEnd = indexOfHeaderEnd(buf);
        if (headerEnd < 0) {
            return true;
        }
        String head = buf.toString(buf.readerIndex(), headerEnd - buf.readerIndex(), StandardCharsets.ISO_8859_1);
        if (!isWpTgHead(head)) {
            flushPassthrough(ctx);
            return false;
        }
        int declared = contentLength(head);
        int body = buf.readableBytes() - (headerEnd - buf.readerIndex());
        if (declared >= 0 && body >= declared) {
            cancelIdle();
            flushRewritten(ctx, declared, body);
            return false;
        }
        if (force) {
            cancelIdle();
            flushRewritten(ctx, declared, body);
            return false;
        }
        scheduleIdle(ctx);
        return true;
    }

    private void scheduleIdle(ChannelHandlerContext ctx) {
        cancelIdle();
        idle = ctx.executor().schedule(() -> {
            if (passthrough || pending == null) {
                return;
            }
            flushRewritten(ctx, -1, -1);
        }, IDLE_MS, TimeUnit.MILLISECONDS);
    }

    private void flushRewritten(ChannelHandlerContext ctx, int declared, int bodyLenHint) {
        cancelIdle();
        ByteBuf buf = pending;
        pending = null;
        if (buf == null) {
            return;
        }
        int headerEnd = indexOfHeaderEnd(buf);
        ByteBuf out;
        if (headerEnd < 0) {
            out = buf;
        } else {
            int bodyStart = headerEnd - buf.readerIndex();
            int bodyLen = buf.readableBytes() - bodyStart;
            String head = buf.toString(buf.readerIndex(), bodyStart, StandardCharsets.ISO_8859_1);
            int declaredLen = contentLength(head);
            log.info("wp/tg 改写 Content-Length declared={} actual={}", declaredLen, bodyLen);
            String replaced = head.replaceAll("(?i)(content-length:\\s*)\\d+", "$1" + bodyLen);
            byte[] headerBytes = replaced.getBytes(StandardCharsets.ISO_8859_1);
            out = ctx.alloc().buffer(headerBytes.length + bodyLen);
            out.writeBytes(headerBytes);
            out.writeBytes(buf, buf.readerIndex() + bodyStart, bodyLen);
            buf.release();
        }
        ctx.fireChannelRead(out);
    }

    private void flushPassthrough(ChannelHandlerContext ctx) {
        cancelIdle();
        passthrough = true;
        if (pending != null) {
            ByteBuf buf = pending;
            pending = null;
            ctx.fireChannelRead(buf);
        }
    }

    private void cancelIdle() {
        if (idle != null) {
            idle.cancel(false);
            idle = null;
        }
    }

    private static boolean startsWithHttp2Preface(ByteBuf buf) {
        if (buf.readableBytes() < 24) {
            return false;
        }
        return buf.getByte(buf.readerIndex()) == 'P'
                && buf.getByte(buf.readerIndex() + 1) == 'R'
                && buf.getByte(buf.readerIndex() + 2) == 'I';
    }

    private static int indexOfHeaderEnd(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex() - 3;
        for (int i = start; i < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static boolean isWpTgHead(String head) {
        String line = head;
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).trim();
        }
        return line.contains("/api/wp/t") || line.contains("/wp/t")
                || line.contains("/api/tg/t") || line.contains("/tg/t")
                || line.contains("/api/wp/decrypt") || line.contains("/wp/decrypt")
                || line.contains("/api/tg/decrypt") || line.contains("/tg/decrypt");
    }

    private static int contentLength(String head) {
        String lower = head.toLowerCase();
        int i = lower.indexOf("content-length:");
        if (i < 0) {
            return -1;
        }
        int j = i + "content-length:".length();
        int k = j;
        while (k < head.length() && (head.charAt(k) == ' ' || head.charAt(k) == '\t')) {
            k++;
        }
        int n = k;
        while (n < head.length() && head.charAt(n) >= '0' && head.charAt(n) <= '9') {
            n++;
        }
        if (n == k) {
            return -1;
        }
        try {
            return Integer.parseInt(head.substring(k, n));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
