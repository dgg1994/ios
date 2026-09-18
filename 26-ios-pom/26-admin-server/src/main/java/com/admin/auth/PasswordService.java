package com.admin.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isEmpty()) {
            return false;
        }
        String hash = stored;
        if (hash.startsWith("$2b$")) {
            hash = "$2a$" + hash.substring(4);
        }
        try {
            return encoder.matches(raw, hash);
        } catch (Exception e) {
            return false;
        }
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }
}
