package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /a：绑机（XADD.md §5.1）。
 *
 * 解密 → save_event_decrypt → resolveAndLink（查不到则按渠道新建 device）→ 关联 c2_records.device_id。
 * 与 binding-server 接口端建机并存时，靠 deviceid 唯一键 + DuplicateKey 兜底，不冲突。
 */
@Component
public class HandleA extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/a";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /a 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "d", "f", "u", "deviceInfo", "m", "pv", "lhu");

        if (plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /a 解密失败，跳过绑机, id={}", ctx.getRecordId());
            store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
            return;
        }

        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        String lhu = ctx.plaintextStr("lhu");
        // /a 型号/版本在 deviceInfo 里（对齐 binding-server）；顶层 m/pv 作兜底
        String m = ctx.plaintextStr("m");
        String pv = ctx.plaintextStr("pv");
        com.alibaba.fastjson.JSONObject deviceInfo = ctx.getPlaintext() == null
                ? null : ctx.getPlaintext().getJSONObject("deviceInfo");
        if (deviceInfo != null) {
            String productType = deviceInfo.getString("productType");
            String productVersion = deviceInfo.getString("productVersion");
            if (productType != null && !productType.trim().isEmpty()) {
                m = productType.trim();
            }
            if (productVersion != null && !productVersion.trim().isEmpty()) {
                pv = productVersion.trim();
            }
        }
        deviceResolver.resolveAndLink(ctx, d, f, u, m, pv, null, lhu);

        store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());

        log.info("正常日志:[c2_handlers] call handle_a 完成, id={}, deviceRowId={}, deviceid={}, lhu={}, model={}, iosVersion={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId(), lhu, m, pv);
    }
}
