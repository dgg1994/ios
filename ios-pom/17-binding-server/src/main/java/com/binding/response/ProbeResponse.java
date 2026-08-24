package com.binding.response;

import lombok.Data;

/**
 * GET /a 探测响应
 * 参考 接口.md §1
 */
@Data
public class ProbeResponse {

    private Boolean ok;

    private String capturedAt;

    private Long bodyBytes;

    public ProbeResponse(Boolean ok, String capturedAt, Long bodyBytes) {
        this.ok = ok;
        this.capturedAt = capturedAt;
        this.bodyBytes = bodyBytes;
    }
}
