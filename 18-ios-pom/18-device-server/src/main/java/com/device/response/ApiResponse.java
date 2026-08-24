package com.device.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class ApiResponse {
    
    private String type = "noop";
    
    /** 接口文档：JSON 字段名为 client_ip（下划线） */
    @JsonProperty("client_ip")
    private String clientIp;
    
    private String status = "ok";
    
    /**
     * 便捷构造方法
     */
    public ApiResponse(String clientIp) {
        this.clientIp = clientIp;
        this.type = "noop";
        this.status = "ok";
    }
    
    /**
     * 工厂方法：成功响应
     */
    public static ApiResponse success(String clientIp) {
        return new ApiResponse(clientIp);
    }
    
    /**
     * 工厂方法：自定义响应
     */
    public static ApiResponse of(String clientIp, String type, String status) {
        ApiResponse resp = new ApiResponse(clientIp);
        resp.setType(type);
        resp.setStatus(status);
        return resp;
    }
}