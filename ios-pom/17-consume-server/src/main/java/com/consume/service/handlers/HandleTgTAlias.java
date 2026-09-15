package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.consume.service.C2Handler;
import com.consume.service.C2HandlerContext;

/** 别名：/tg/t → 同 {@link HandleTgT} */
@Component
public class HandleTgTAlias implements C2Handler {

    @Autowired
    private HandleTgT delegate;

    @Override
    public String path() {
        return "/tg/t";
    }

    @Override
    public void handle(C2HandlerContext ctx) throws Exception {
        delegate.handle(ctx);
    }
}
