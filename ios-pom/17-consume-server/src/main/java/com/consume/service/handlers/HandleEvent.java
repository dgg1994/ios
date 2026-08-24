package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /event：事件 + 可绑机（XADD.md §5.2）。
 *
 * 解密 → save_event_decrypt → bind_device(d/f) → insert c2_event_records → 关联 c2_records.device_id。
 */
@Component
public class HandleEvent extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/event";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /event 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "et", "d", "f", "u", "deviceInfo", "lhu");

        if (plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /event 解密失败，跳过业务, id={}", ctx.getRecordId());
            store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
            return;
        }

        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        String m = ctx.plaintextStr("m");
        String pv = ctx.plaintextStr("pv");
        String lhu = ctx.plaintextStr("lhu");
        Long rowId = deviceResolver.resolveAndLink(ctx, d, f, u, m, pv, null, lhu);

        store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
        if (rowId == null) {
            log.info("异常日志:[c2_handlers] /event 无设备 rowId，跳过 c2_event_records, id={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        store.insertEventRecord(ctx, ctx.getPlaintext());

        log.info("正常日志:[c2_handlers] call handle_event 完成, id={}, deviceRowId={}, channelcode={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getChannelcode());
    }
}
