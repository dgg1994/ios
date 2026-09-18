package com.admin.web;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.admin.auth.AdminHolder;
import com.admin.auth.AuthService;
import com.admin.dto.ApiResponse;
import com.admin.dto.LoginRequest;
import com.admin.util.AdminUnauthorizedException;
import com.admin.util.ClientIpUtil;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> loginJson(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse resp) {
        return doLogin(body == null ? null : body.getUsername(), body == null ? null : body.getPassword(), req, resp);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> loginForm(@RequestParam String username, @RequestParam String password,
            HttpServletRequest req, HttpServletResponse resp) {
        return doLogin(username, password, req, resp);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return ApiResponse.ok(authService.session(AdminHolder.require()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse resp) {
        authService.logout(resp);
        return ApiResponse.ok("ok", null);
    }

    private Map<String, Object> doLogin(String username, String password, HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, Object> data = authService.login(username, password, ClientIpUtil.of(req), resp);
            return ApiResponse.ok(data);
        } catch (AdminUnauthorizedException e) {
            return ApiResponse.fail(e.getMessage(), 1);
        }
    }
}
