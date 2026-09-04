package com.agentplatform.core.security;

import com.agentplatform.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * core 侧 JWT 兜底鉴权过滤器。
 * <p>
 * 生产环境直连 8081 时不再仅依赖可伪造的 {@code X-Tenant-Id} 头：开关
 * {@code agent-platform.security.enabled}（默认 {@code false}，保证本机直连 / 单测
 * 驱动仪表盘可用）。开启后校验 {@code Authorization: Bearer}，并从 Claims 注入
 * {@code X-Tenant-Id} / {@code X-User-Id}，白名单（health / auth / 静态资源）放行。
 * </p>
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final String jwtSecret;

    public JwtAuthFilter(
            @Value("${agent-platform.security.enabled:false}") boolean enabled,
            @Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret) {
        this.enabled = enabled;
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        return isWhitelisted(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "Missing Bearer token");
            return;
        }

        try {
            Claims claims = JwtUtil.parseToken(jwtSecret, authHeader.substring(7));
            String tenantId = JwtUtil.tenantId(claims);
            String userId = claims.get("user_id", String.class);

            // 防跨租户越权：调用方自行带上的 X-Tenant-Id 若与 token 归属不一致则拒绝。
            // （不依赖该头伪造租户：即使恶意传别的租户，也会被 403 拦下）
            String callerTenant = request.getHeader("X-Tenant-Id");
            if (callerTenant != null && !callerTenant.isBlank()
                    && tenantId != null && !tenantId.equals(callerTenant)) {
                log.warn("Tenant mismatch: token tenant={} vs header tenant={}, reject",
                        tenantId, callerTenant);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json; charset=UTF-8");
                response.getWriter().write(
                        "{\"success\":false,\"code\":\"FORBIDDEN\",\"message\":\"X-Tenant-Id 与 token 归属租户不一致\"}");
                return;
            }

            HttpServletRequest wrapped = new HeaderOverrideRequest(request, tenantId, userId);
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            log.warn("JWT validation failed on core side: {}", e.getMessage());
            unauthorized(response, "Invalid token");
        }
    }

    private boolean isWhitelisted(String path) {
        if (path.startsWith("/actuator/health") || path.startsWith("/api/v1/auth/")) {
            return true;
        }
        // 非 API 路径（仪表盘静态资源 / SPA）放行
        return !path.startsWith("/api/");
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }

    /**
     * 用 JWT Claims 覆盖请求头，供下游控制器消费可信身份。
     */
    private static final class HeaderOverrideRequest extends HttpServletRequestWrapper {

        private final String tenantId;
        private final String userId;

        HeaderOverrideRequest(HttpServletRequest request, String tenantId, String userId) {
            super(request);
            this.tenantId = tenantId;
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name) && tenantId != null) {
                return tenantId;
            }
            if ("X-User-Id".equalsIgnoreCase(name) && userId != null) {
                return userId;
            }
            return super.getHeader(name);
        }
    }
}