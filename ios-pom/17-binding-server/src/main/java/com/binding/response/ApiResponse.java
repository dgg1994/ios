package com.binding.response;

import lombok.Data;

@Data
public class ApiResponse {

    private String msg;

    private Integer code;

    public ApiResponse(String msg, Integer code) {
        this.msg = msg;
        this.code = code;
    }
}
