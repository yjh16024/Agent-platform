package com.agentplatform.core.agent.controller;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JwtUtil;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RbacService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鉴权接口（签发 JWT）。
 * <p>
 * 账号策略（MVP → 生产渐进）—— 由 {@code agent-platform.security.rbac.enabled} 分流：
 * <ul>
 *   <li><b>RBAC 开启</b>：校验 {@code sys_user} 表（BCrypt 哈希），签发的 token 带
 *       {@code roles}，下游据此做权限控制；</li>
 *   <li><b>RBAC 关闭（默认）</b>：沿用原行为 —— 配置了静态账号
 *       （{@code AUTH_USERNAME} / {@code AUTH_PASSWORD}）则必须一致；未配置则按演示模式
 *       直接签发 {@code {tenant_id,user_id}}。</li>
 * </ul>
 * </p>
 * <p>
 * 接口路径、入参与返回结构在两条分支下**保持一致**（RBAC 分支额外多返回
 * {@code roles} / {@code display_name} 两个字段），因此前端登录代码无需改动。
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
    private final boolean securityEnabled;
    private final boolean rbacEnabled;
    private final RbacService rbacService;

    public AuthController(
            @Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret,
            @Value("${agent-platform.jwt.expire-seconds:86400}") long expireSeconds,
            @Value("${AUTH_USERNAME:}") String staticUsername,
            @Value("${AUTH_PASSWORD:}") String staticPassword,
            @Value("${agent-platform.security.enabled:false}") boolean securityEnabled,
            @Value("${agent-platform.security.rbac.enabled:false}") boolean rbacEnabled,
            RbacService rbacService) {
        this.jwtSecret = jwtSecret;
        this.expireSeconds = expireSeconds;
        this.staticUsername = staticUsername == null ? "" : staticUsername.trim();
        this.staticPassword = staticPassword == null ? "" : staticPassword.trim();
        this.securityEnabled = securityEnabled;
        this.rbacEnabled = rbacEnabled;
        this.rbacService = rbacService;
    }

    /**
     * 当前鉴权模式（供前端决定"要不要显示登录页"）。
     *
     * <p>为什么必须有这个接口：桌面（embedded）profile **显式关闭了鉴权**，
     * 如果前端无脑要求登录，桌面版会被自己的登录页挡在门外。前端启动时先问这里，
     * {@code security_enabled=false} 就直接放行。</p>
     *
     * <p>它必须能被**匿名访问**（`/api/v1/auth/**` 在后端白名单里）——
     * 否则会死锁：没登录 → 拿不到模式 → 跳登录页 → 登录页又要先拿模式。</p>
     */
    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> mode() {
        return ApiResponse.ok(Map.of(
                "security_enabled", securityEnabled,
                "rbac_enabled", rbacEnabled
        ));
    }

    /**
     * 当前登录用户的身份与权限（供前端控制菜单/入口的显示）。
     *
     * <p><b>它不在匿名白名单里</b> —— 必须在 {@code JwtAuthFilter} 之后执行，
     * 否则 {@code ap.perms} 根本没被写入，返回的权限集永远是空的。</p>
     *
     * <p><b>安全边界</b>：前端拿它**只用来隐藏入口**，真正的拦截在后端
     * （{@code @RequiresPermission} + {@code PermissionAspect}）。
     * 看不到菜单 ≠ 调不动接口，所以这里返回什么都不构成提权风险；
     * 反过来，如果只靠前端隐藏而后端不拦，才是真漏洞。</p>
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", RbacContext.userId());
        data.put("tenant_id", RbacContext.tenantId());
        data.put("roles", RbacContext.roles());
        data.put("perms", RbacContext.permissions());
        data.put("security_enabled", securityEnabled);
        data.put("rbac_enabled", rbacEnabled);
        return ApiResponse.ok(data);
    }

    /**
     * 登录换取 JWT。
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String tenantId = body == null ? "default"
                : blankToDefault(body.get("tenant_id"), "default");
        String username = body == null ? "" : blankToEmpty(body.get("username"));
        String password = body == null ? "" : blankToEmpty(body.get("password"));

        if (rbacEnabled) {
            return rbacLogin(tenantId, username, password);
        }
        return legacyLogin(tenantId, username, password);
    }

    /**
     * RBAC 分支：查 sys_user 表，签发带 roles 的 token。
     */
    private ApiResponse<Map<String, Object>> rbacLogin(String tenantId, String username, String password) {
        RbacService.LoginResult r = rbacService.authenticate(tenantId, username, password);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tenant_id", r.tenantId());
        claims.put("user_id", r.userId());
        // 只放角色码：权限点由服务端按角色推导（改角色权限即时生效）
        claims.put("roles", new ArrayList<>(r.roleCodes()));

        String token = JwtUtil.generateToken(jwtSecret, r.userId(), expireSeconds, claims);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expires_in", expireSeconds);
        data.put("tenant_id", r.tenantId());
        data.put("user_id", r.userId());
        data.put("display_name", r.displayName());
        data.put("roles", r.roleCodes());
        return ApiResponse.ok(data);
    }

    /**
     * 兼容分支（RBAC 关闭）：静态账号，或未配置时的演示模式。
     * <p>保留原样，保证桌面 embedded、本机开发与既有单测行为不变。</p>
     */
    private ApiResponse<Map<String, Object>> legacyLogin(String tenantId, String username, String password) {
        if (!staticUsername.isEmpty() || !staticPassword.isEmpty()) {
            if (!staticUsername.equals(username) || !staticPassword.equals(password)) {
                throw BizException.unauthorized("用户名或密码错误");
            }
        } else {
            log.warn("[auth] 未配置 AUTH_USERNAME/AUTH_PASSWORD，按演示模式签发 token。"
                    + " 生产环境请配置静态账号、开启 RBAC 或接入 OAuth2/LDAP。");
        }

        String userId = (username == null || username.isBlank()) ? "demo-user" : username;

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

    private String blankToDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
