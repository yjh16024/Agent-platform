package com.agentplatform.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.agentplatform.common.exception.BizException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * 从 Claims 提取 user_id。
     */
    public static String userId(Claims claims) {
        Object v = claims.get("user_id");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 从 Claims 提取 roles（角色码列表，如 {@code ["admin"]}）。
     *
     * <p>token 里**只放角色码、不放具体权限点**：权限由角色在服务端推导（可缓存）。
     * 这样管理员调整「角色→权限」后立刻生效，只有调整「用户→角色」才需要重新登录 ——
     * 反之若把权限点写进 token，任何权限变更都得等 token 过期，实践中很难接受。</p>
     *
     * <p>返回不可变列表；claim 缺失或类型不符时返回空列表，绝不抛异常 ——
     * 调用方是鉴权过滤器，token 里没有 roles 只意味着"没有角色"，不该变成 500。</p>
     */
    public static List<String> roles(Claims claims) {
        Object v = claims.get("roles");
        if (v instanceof Collection<?> c) {
            return c.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
        }
        return List.of();
    }
}