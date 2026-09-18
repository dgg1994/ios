package com.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadyController {

    @Value("${v26.admin-path:/x7kQ2mNp9vR4sT8w}")
    private String adminPath;

    @GetMapping({"/", "/api/admin/ready", "/api/v1/system/ready"})
    public Map<String, Object> ready() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "26-admin-server");
        data.put("adminPathHint", adminPath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("data", data);
        return body;
    }
}
