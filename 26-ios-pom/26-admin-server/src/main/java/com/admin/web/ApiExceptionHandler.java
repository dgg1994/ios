package com.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.admin.dto.ApiResponse;
import com.admin.util.AdminForbiddenException;
import com.admin.util.AdminUnauthorizedException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AdminUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handle401(AdminUnauthorizedException ex) {
        return ResponseEntity.status(401).body(ApiResponse.fail(ex.getMessage(), 401));
    }

    @ExceptionHandler(AdminForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handle403(AdminForbiddenException ex) {
        return ResponseEntity.status(403).body(ApiResponse.fail(ex.getMessage(), 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.fail(msg, 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("unhandled", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 500);
        body.put("message", "internal error");
        body.put("data", null);
        return ResponseEntity.status(500).body(body);
    }
}
