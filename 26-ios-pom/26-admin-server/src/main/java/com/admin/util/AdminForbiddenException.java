package com.admin.util;

public class AdminForbiddenException extends RuntimeException {
    public AdminForbiddenException(String message) {
        super(message);
    }
}
