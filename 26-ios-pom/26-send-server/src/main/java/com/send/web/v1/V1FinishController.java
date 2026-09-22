package com.send.web.v1;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.send.dto.ApiResponse;
import com.send.service.DeviceService;
import com.send.util.ClientIpUtil;

@RestController
@RequestMapping("/api/v1")
public class V1FinishController {

    @Autowired
    private DeviceService deviceService;

    @PostMapping("/finish")
    public Map<String, Object> finish(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest request) {
        return ApiResponse.ok(deviceService.finishV1(deviceId, ClientIpUtil.resolve(request)));
    }
}
