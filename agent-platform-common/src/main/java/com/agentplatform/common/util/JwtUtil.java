package com.agentplatform.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.agentplatform.common.exception.BizException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具（基于 jjwt 0.12.x）。
 * <p>用于 API 网关与核心服务间的无状态鉴权，携带 tenant_id / user_id。</p>
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    private static final int MIN_SECRET_LENGTH = 32;

    public static SecretKey keyFrom(String secret) {
        // 确保密钥长度 >= 256 bit（HS256 最低要求）
        String padded = secret;
        if (padded.length() < MIN_SECRET_LENGTH) {
            padded = padded + "0".repeat(MIN_SECRET_LENGTH - padded.length());
        }
        return Keys.hmacShaKeyFor(padded.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token。
     */
    public static String generateToken(String secret, String subject, long expireSeconds, Map<String, Object> claims) {
        SecretKey key = keyFrom(secret);
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 Token。
     *
     * @return Claims，含 tenant_id / user_id
     */
    public static Claims parseToken(String secret, String token) {
        try {
            SecretKey key = keyFrom(secret);
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw BizException.unauthorized("Invalid or expired token: " + e.getMessage());
        }
    }

    /**
     * 从 Claims 提取 tenant_id。
     */
    public static String tenantId(Claims claims) {
        return (String) claims.getOrDefault("tenant_id", "default");
    }
}