package com.agentplatform.core.agent.controller;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 鉴权接口（签发 JWT）。
 * <p>
 * 账号策略（MVP → 生产渐进）：
 * <ul>
 *   <li>配置了静态账号（{@code AUTH_USERNAME} / {@code AUTH_PASSWORD} 环境变量）→
 *       登录必须提供一致的 username/password，否则 401；</li>
 *   <li>未配置（本地/演示默认）→ 沿用 {@code {tenant_id,user_id}} 直接签发，
 *       并打印告警提示生产环境必须配置账号或接入 OAuth2/LDAP。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final String jwtSecret;
    private final long expireSeconds;
    private final String staticUsername;
    private final String staticPassword;

    public AuthController(
            @Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret,
            @Value("${agent-platform.jwt.expire-seconds:86400}") long expireSeconds,
            @Value("${AUTH_USERNAME:}") String staticUsername,
            @Value("${AUTH_PASSWORD:}") String staticPassword) {
        this.jwtSecret = jwtSecret;
        this.expireSeconds = expireSeconds;
        this.staticUsername = staticUsername == null ? "" : staticUsername.trim();
        this.staticPassword = staticPassword == null ? "" : staticPassword.trim();
    }

    /**
     * 登录换取 JWT。
     * <p>配置了静态账号时 body 需含 username/password；否则按演示模式签发
     * {@code {tenant_id, user_id}}。</p>
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        if (!staticUsername.isEmpty() || !staticPassword.isEmpty()) {
            String u = body == null ? "" : blankToEmpty(body.get("username"));
            String p = body == null ? "" : blankToEmpty(body.get("password"));
            if (!staticUsername.equals(u) || !staticPassword.equals(p)) {
                throw BizException.unauthorized("用户名或密码错误");
            }
        } else {
            log.warn("[auth] 未配置 AUTH_USERNAME/AUTH_PASSWORD，按演示模式签发 token。"
                    + " 生产环境请配置静态账号或接入 OAuth2/LDAP。");
        }

        String tenantId = body == null ? "default" : blankToEmpty(body.getOrDefault("tenant_id", "default"));
        String userId = body == null ? "demo-user" : blankToEmpty(body.getOrDefault("user_id", "demo-user"));

        String token = JwtUtil.generateToken(jwtSecret, userId, expireSeconds, Map.of(
                "tenant_id", tenantId,
                "user_id", userId
        ));
        return ApiResponse.ok(Map.of(
                "token", token,
                "expires_in", expireSeconds,
                "tenant_id", tenantId,
                "user_id", userId
        ));
    }

    private String blankToEmpty(String v) {
        return v == null ? "" : v;
    }
}
