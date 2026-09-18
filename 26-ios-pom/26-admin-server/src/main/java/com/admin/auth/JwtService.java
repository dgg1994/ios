package com.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final V26AdminProperties props;

    public String issue(Integer userId, String roleCode, int tokenVersion) {
        Date now = new Date();
        long expMs = props.getJwt().getExpireMinutes() * 60L * 1000L;
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("typ", "admin")
                .claim("role", roleCode == null ? "" : roleCode)
                .claim("tv", tokenVersion)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expMs))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Map<String, Object> asMap(Claims claims) {
        return claims;
    }

    private SecretKey signingKey() {
        byte[] raw = (props.getJwt().getSecret() == null ? "" : props.getJwt().getSecret())
                .getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            try {
                raw = MessageDigest.getInstance("SHA-256").digest(raw);
            } catch (Exception e) {
                raw = Arrays.copyOf(raw, 32);
            }
        }
        return Keys.hmacShaKeyFor(raw);
    }
}
