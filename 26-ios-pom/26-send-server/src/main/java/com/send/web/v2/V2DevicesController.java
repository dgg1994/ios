package com.send.web.v2;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.send.dto.ApiResponse;
import com.send.dto.DeviceRegisterRequest;
import com.send.service.DeviceService;
import com.send.util.ClientIpUtil;

@RestController
@RequestMapping("/api/v2/devices")
public class V2DevicesController {

    @Autowired
    private DeviceService deviceService;

    @PostMapping({"", "/"})
    public Map<String, Object> register(@Valid @RequestBody DeviceRegisterRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(deviceService.registerV2(body, ClientIpUtil.resolve(request)));
    }
}
