package com.send.web.v1;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.send.dto.ApiResponse;
import com.send.dto.DeviceRegisterRequest;
import com.send.service.DeviceService;
import com.send.util.ClientIpUtil;

@RestController
@RequestMapping("/api/v1/devices")
public class V1DevicesController {

    @Autowired
    private DeviceService deviceService;

    @PostMapping({"", "/"})
    public Map<String, Object> register(@Valid @RequestBody DeviceRegisterRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(deviceService.registerV1(body, ClientIpUtil.resolve(request)));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(deviceService.deviceMe(deviceId));
    }
}
