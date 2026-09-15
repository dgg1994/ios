package com.consume.service.handlers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.consume.service.C2Handler;
import com.consume.service.C2HandlerContext;

/** 别名：/wp/t → 同 {@link HandleWpT} */
@Component
public class HandleWpTAlias implements C2Handler {

    @Autowired
    private HandleWpT delegate;

    @Override
    public String path() {
        return "/wp/t";
    }

    @Override
    public void handle(C2HandlerContext ctx) throws Exception {
        delegate.handle(ctx);
    }
}
