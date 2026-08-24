package com.consume.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consume.util.EventDecryptor;

/**
 * C2 handler 抽象基类：提供通用「解密 + 明文回退」与日志能力，
 * 各 path 子类只需实现业务落库部分。
 *
 * 解密逻辑与 report-server UsDeviceBindService.decryptSafely / extractBodyText 一致。
 */
public abstract class AbstractC2Handler implements C2Handler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected DeviceResolver deviceResolver;

    /**
     * 执行解密并把结果写回 ctx。
     * <p>
     * 注意：binding-server 的 /a（以及部分接口侧）入库时已把 body 写成明文 JSON，
     * 此处会先识别明文，避免再走 AES 并打出「解密失败」误导日志。
     *
     * @param ctx        上下文
     * @param plainHints 明文判断字段（如 "d"/"f"/"u"）
     * @return 明文 JSON 文本；都失败返回空串
     */
    protected String decrypt(C2HandlerContext ctx, String... plainHints) {
        String xTs = ctx.getXTs();
        String body = ctx.getBody();
        ctx.setSuccess(0);
        ctx.setKeyLabel("");
        ctx.setPrefix("");

        // 1) 已是明文 JSON（接口侧已解密入库）→ 直接用，不算失败
        String asPlain = tryPlaintextBody(ctx, body, plainHints);
        if (asPlain != null) {
            ctx.setKeyLabel("already-plaintext");
            ctx.setDecryptOk(true);
            ctx.setDecryptError("");
            ctx.setSuccess(1);
            log.debug("正常日志:[c2_handlers] body 已是明文，跳过 AES, id={}, path={}",
                    ctx.getRecordId(), ctx.getPath());
            return asPlain;
        }

        // 2) AES 解密（c2_records.body 仍是密文时）
        try {
            java.util.Map<String, Object> r = EventDecryptor.decryptEventBody(xTs, body);
            if (r != null) {
                ctx.setKeyLabel(nullToEmpty(r.get("key_label")));
                ctx.setPrefix(nullToEmpty(r.get("prefix")));
                if (Boolean.TRUE.equals(r.get("success"))) {
                    Object pt = r.get("plaintext");
                    if (pt instanceof JSONObject) {
                        String json = JSON.toJSONString(pt);
                        ctx.setPlaintext((JSONObject) pt);
                        ctx.setPlaintextJson(json);
                        ctx.setRawText(nullToEmpty(r.get("raw")));
                        ctx.setDecryptOk(true);
                        ctx.setDecryptError("");
                        ctx.setSuccess(1);
                        return json;
                    }
                }
            }
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers] AES 解密异常, id={}, path={}, err={}",
                    ctx.getRecordId(), ctx.getPath(), e.getMessage());
        }

        ctx.setDecryptOk(false);
        ctx.setDecryptError("decrypt-failed");
        log.info("异常日志:[c2_handlers] 解密失败且无明文可回退, id={}, path={}, xTs={}",
                ctx.getRecordId(), ctx.getPath(), xTs);
        return "";
    }

    /** body 已是带 hint 字段的 JSON 则写入 ctx 并返回原文，否则 null */
    private static String tryPlaintextBody(C2HandlerContext ctx, String body, String[] plainHints) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String t = body.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            if (obj != null && hasAnyHint(obj, plainHints)) {
                ctx.setPlaintext(obj);
                ctx.setPlaintextJson(body);
                ctx.setRawText(body);
                return body;
            }
        } catch (Exception ignore) {
            // 不是合法 JSON
        }
        return null;
    }

    /** 解析 domain（从 headers Host），并写入 ctx；找不到返回空串 */
    protected String resolveDomain(C2HandlerContext ctx) {
        String domain = com.consume.util.HeadersUtil.domainFromHost(ctx.getHeadersJson());
        ctx.setDomain(domain);
        return domain;
    }

    private static boolean hasAnyHint(JSONObject obj, String[] hints) {
        if (hints == null || hints.length == 0) {
            return true;
        }
        for (String h : hints) {
            if (h != null && obj.containsKey(h)) {
                return true;
            }
        }
        return false;
    }

    protected static String nullToEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    protected static String strOrEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 仅用于消除未使用告警 */
    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return null;
    }
}
