package com.agentplatform.core.security.rbac;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Set;

/**
 * 当前请求的身份与权限（只读视图）。
 *
 * <h3>数据来源</h3>
 * 全部由 {@code JwtAuthFilter} 在校验 token 后写入请求属性/头，
 * 这里只是把它读出来，**不做任何鉴权判断**：
 * <ul>
 *   <li>{@code ap.perms} / {@code ap.roles}：过滤器解析 token 后写入的请求属性；</li>
 *   <li>{@code X-User-Id} / {@code X-Tenant-Id}：过滤器用 token 里的值**覆盖**过的请求头
 *       （项目既有约定，Controller 里一直在用）。</li>
 * </ul>
 *
 * <h3>拿不到请求上下文时怎么办</h3>
 * 返回空值/空集合，**不抛异常**。典型场景：虚拟线程或 {@code @Async} 里调用 ——
 * {@code RequestContextHolder} 默认不跨线程传递。此时"权限为空"会让
 * {@code PermissionAspect} 直接拒绝，属于**安全方向的失败**（fail-closed），
 * 比"以为有权限"要好。若将来真要在异步链路里做鉴权，应显式传递权限集合，
 * 而不是放开这里的默认值。
 */
public final class RbacContext {

    private RbacContext() {
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /** 本次请求的权限码集合；无请求上下文时为空集。 */
    @SuppressWarnings("unchecked")
    public static Set<String> permissions() {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return Set.of();
        }
        Object v = req.getAttribute(RbacService.ATTR_PERMS);
        return v instanceof Set<?> s ? (Set<String>) s : Set.of();
    }

    /**
     * 本次请求的角色码集合；无请求上下文时为空集。
     *
     * <p>与 {@link #permissions()} 的区别：权限是"服务端按角色推导"出来的（几经查库+缓存），
     * 角色则是 token 里直接带的。前端要区分"我是管理员"和"我能做某事"时用得到前者。</p>
     */
    @SuppressWarnings("unchecked")
    public static List<String> roles() {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return List.of();
        }
        Object v = req.getAttribute(RbacService.ATTR_ROLES);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    /** 本次请求是否具备某权限。 */
    public static boolean has(String permCode) {
        return permCode != null && permissions().contains(permCode);
    }

    /** 当前用户 ID（未鉴权/演示模式下可能为 null）。 */
    public static String userId() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : req.getHeader("X-User-Id");
    }

    /** 当前租户 ID。 */
    public static String tenantId() {
        HttpServletRequest req = currentRequest();
        if (req != null) {
            String t = req.getHeader("X-Tenant-Id");
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        return "default";
    }
}
