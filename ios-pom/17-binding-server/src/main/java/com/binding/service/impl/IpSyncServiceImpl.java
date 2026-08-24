package com.binding.service.impl;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.binding.query.IpSyncQuery;
import com.binding.response.ApiResponse;
import com.binding.service.AsyncIpSyncDeviceService;
import com.binding.service.IpSyncService;
import com.binding.util.IpUtil;
import com.binding.util.UserAgentUtil;

/**
 * 原 17-ctwo-server /api/ip-sync 实现，已合并到 17-binding-server。
 */
@RestController
@Transactional
@CrossOrigin
public class IpSyncServiceImpl implements IpSyncService {

    @Autowired
    private IpUtil ipUtil;

    @Autowired
    private AsyncIpSyncDeviceService asyncIpSyncDeviceService;

    @SuppressWarnings("serial")
    private static final HttpHeaders FIXED_HEADERS = new HttpHeaders() {{
        add("Server", "cloudflare");
        add("CF-RAY", "8a9b0c1d2e3f4g5h");
    }};

    @Override
    public ResponseEntity<ApiResponse> syncPost(HttpServletRequest request, @RequestBody IpSyncQuery query) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(FIXED_HEADERS);
            String userAgent = request.getHeader("User-Agent");
            if (UserAgentUtil.isBlockedOs(userAgent)) {
                headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
                return new ResponseEntity<>(new ApiResponse("OK", 200), headers, HttpStatus.OK);
            }
            String realIp = ipUtil.getClientIp(request);
            asyncIpSyncDeviceService.insertAsync(query, realIp);
            headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
            return new ResponseEntity<>(new ApiResponse("OK", 200), headers, HttpStatus.OK);
        } catch (Exception e) {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(FIXED_HEADERS);
            headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
            return new ResponseEntity<>(new ApiResponse("OK", 200), headers, HttpStatus.OK);
        }
    }

    @Override
    public ResponseEntity<ApiResponse> syncGet(HttpServletRequest request, IpSyncQuery query) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(FIXED_HEADERS);
            headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
            return new ResponseEntity<>(new ApiResponse("OK", 200), headers, HttpStatus.OK);
        } catch (Exception e) {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(FIXED_HEADERS);
            headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
            return new ResponseEntity<>(new ApiResponse("OK", 200), headers, HttpStatus.OK);
        }
    }
}
