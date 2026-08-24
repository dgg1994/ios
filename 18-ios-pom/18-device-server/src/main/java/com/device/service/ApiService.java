package com.device.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.device.response.ApiResponse;

/**
 * @category 设备注册、绑定、状态上报、心跳检测
 * @author Hlin
 *
 */
@RequestMapping
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public interface ApiService {

    @PostMapping("/api/device/register")
    ResponseEntity<String> postDeviceRegister(HttpServletRequest request);

    @PostMapping(value = {"/a", "/api/a"})
    ResponseEntity<String> postA(HttpServletRequest request);

    @GetMapping(value = {"/a", "/api/a"})
    ResponseEntity<String> getA(HttpServletRequest request);

    @PostMapping(value = {"/event", "/api/event"})
    ResponseEntity<String> postEvent(HttpServletRequest request);

    // ==================== 心跳检测（原 18-beacon-server） ====================

    @PostMapping("/beacon")
    ApiResponse beaconPost(HttpServletRequest request);

    @PostMapping("/api/beacon")
    ApiResponse apiBeaconPost(HttpServletRequest request);

    @GetMapping(value = {"/beacon", "/api/beacon"})
    ResponseEntity<String> getBeacon(HttpServletRequest request);

    @RequestMapping(value = {"/health", "/healthz", "/ready"},
            method = {RequestMethod.GET, RequestMethod.HEAD},
            produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> health(HttpServletRequest request);

    // ==================== OPTIONS + 兜底 ====================

    @RequestMapping(value = {"/a", "/api/a", "/api/device/register", "/beacon", "/api/beacon"}, method = RequestMethod.OPTIONS)
    ResponseEntity<String> handleOptionsKnown(HttpServletRequest request);

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<String> captureUnimplemented(HttpServletRequest request);

    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    ResponseEntity<String> handleOptionsAll(HttpServletRequest request);
}
