package com.admin.util;

public class AdminUnauthorizedException extends RuntimeException {
    public AdminUnauthorizedException(String message) {
        super(message);
    }
}
