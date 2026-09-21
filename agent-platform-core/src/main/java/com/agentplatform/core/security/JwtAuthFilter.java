package com.agentplatform.core.security;

import com.agentplatform.common.util.JwtUtil;
import com.agentplatform.core.security.rbac.RbacService;
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
import java.util.List;

/**
 * core 侧 JWT 兜底鉴权过滤器。
 * <p>
 * 生产环境直连 8081 时不再仅依赖可伪造的 {@code X-Tenant-Id} 头：开关
 * {@code agent-platform.security.enabled}（默认 {@code false}，保证本机直连 / 单测
 * 驱动仪表盘可用）。开启后校验 {@code Authorization: Bearer}，并从 Claims 注入
 * {@code X-Tenant-Id} / {@code X-User-Id}，白名单（health / auth / 静态资源）放行。
 * </p>
 * <p>
 * <b>2026-09-20 扩展（RBAC）</b>：认证之外补上"授权所需的上下文"。开启
 * {@code agent-platform.security.rbac.enabled} 后，额外做一件事 ——
 * 把 token 里的 {@code roles} 解析出来，并结合角色推导出权限集合，写进请求属性
 * {@code ap.perms} / {@code ap.roles}，供 {@code PermissionAspect} 校验。
 * </p>
 * <p>
 * 刻意**不在过滤器里做权限判断**：过滤器只知道"请求路径"，而项目大量接口的语义
 * 取决于参数与实体归属（例如"只能改自己租户的智能体"），路径级规则表达不了。
 * 判断交给注解 + 切面，粒度精确到方法。
 * </p>
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final String jwtSecret;
    /** RBAC 开关：关闭时不解析权限（少一次查库，且与历史行为完全一致）。 */
    private final boolean rbacEnabled;
    private final RbacService rbacService;

    public JwtAuthFilter(
            @Value("${agent-platform.security.enabled:false}") boolean enabled,
            @Value("${agent-platform.jwt.secret:change-me-to-a-256-bit-secret-key-for-production-use-please}") String jwtSecret,
            @Value("${agent-platform.security.rbac.enabled:false}") boolean rbacEnabled,
            RbacService rbacService) {
        this.enabled = enabled;
        this.jwtSecret = jwtSecret;
        this.rbacEnabled = rbacEnabled;
        this.rbacService = rbacService;
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
            String userId = JwtUtil.userId(claims);
            List<String> roles = JwtUtil.roles(claims);

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

            // 授权上下文：写进请求属性（不是请求头 —— 头是字符串，表达集合要拼分隔符且易被伪造）
            wrapped.setAttribute(RbacService.ATTR_ROLES, roles);
            if (rbacEnabled) {
                if (roles.isEmpty()) {
                    // 没有角色 = 没有任何权限。这里显式写空集而不是"不写"：
                    // 不写的话下游无法区分"已鉴权但无权限"与"根本没鉴权"，
                    // 排查时会误以为是过滤器没生效。
                    wrapped.setAttribute(RbacService.ATTR_PERMS, java.util.Set.of());
                } else {
                    try {
                        wrapped.setAttribute(RbacService.ATTR_PERMS,
                                rbacService.permissionsOf(tenantId, roles));
                    } catch (Exception e) {
                        // 查权限失败（如 DB 抖动）按"无权限"处理：fail-closed 比"默认放行"安全
                        log.warn("解析权限失败，按无权限处理：{}", e.getMessage());
                        wrapped.setAttribute(RbacService.ATTR_PERMS, java.util.Set.of());
                    }
                }
            }

            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            log.warn("JWT validation failed on core side: {}", e.getMessage());
            unauthorized(response, "Invalid token");
        }
    }

    private boolean isWhitelisted(String path) {
        if (path.startsWith("/actuator/health") || isAnonymousAuthPath(path)) {
            return true;
        }
        // 非 API 路径（仪表盘静态资源 / SPA）放行
        return !path.startsWith("/api/");
    }

    /**
     * {@code /api/v1/auth} 下**只有这两个接口**是匿名的。
     *
     * <p>2026-09-20 收紧：原来这里用的是 {@code startsWith("/api/v1/auth/")}，
     * 意味着该前缀下**任何**新增接口都会绕过过滤器。而"跳过过滤器"不只是"不强制 token"，
     * 而是**连 token 都不解析** —— 于是 {@code ap.perms} / {@code ap.roles} 不会被写入，
     * 请求属性为空。{@code /api/v1/auth/me}（返回当前用户权限，供前端控制菜单显示）
     * 正是踩在这个坑上：它必须走过滤器才拿得到权限集。</p>
     *
     * <p>当前 {@code /api/v1/auth} 下的接口只有 {@code login} 与 {@code mode} 两个，
     * 所以这次收紧没有行为变化；后续在此前缀下加接口时，
     * **默认需要鉴权**（要匿名必须显式加到这里）。</p>
     */
    private boolean isAnonymousAuthPath(String path) {
        return path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/mode");
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
