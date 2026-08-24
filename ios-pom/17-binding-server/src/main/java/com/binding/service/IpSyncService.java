package com.binding.service;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.binding.query.IpSyncQuery;
import com.binding.response.ApiResponse;

/**
 * 原 17-ctwo-server 的 /api/ip-sync 接口，已合并到 17-binding-server。
 */
@RequestMapping("/api")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public interface IpSyncService {

    @PostMapping("/ip-sync/sync")
    ResponseEntity<ApiResponse> syncPost(HttpServletRequest request, IpSyncQuery query);

    @GetMapping("/ip-sync/sync")
    ResponseEntity<ApiResponse> syncGet(HttpServletRequest request, IpSyncQuery query);
}
