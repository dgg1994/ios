package com.send.util;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    public BizException(int httpStatus, String message) {
        this(httpStatus, httpStatus, message);
    }

    public BizException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
