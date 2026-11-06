package com.agentplatform.core.security.rbac;

import com.agentplatform.framework.api.AuthorizationPolicy;
import com.agentplatform.framework.api.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 平台授权策略：把框架的 {@link AuthorizationPolicy} 接到既有 RBAC 上（P2' 鉴权接缝）。
 *
 * <p>与 {@link PermissionAspect} 的关系：后者挂在 Controller 的注解上，
 * 因此**只在 HTTP 入口生效**；本类补的正是「不经 Controller 直接调运行时」那条路径。
 * 两者共用同一套权限码，所以不会出现「网页能点、内部调用不能跑」的割裂。</p>
 *
 * <p><b>fail-closed</b>：启用 RBAC 却没给调用者身份时直接拒绝。
 * 这不是保守，是唯一正确的默认——放过匿名请求等于鉴权形同虚设。</p>
 */
@Component
public class PlatformAuthorizationPolicy implements AuthorizationPolicy {

    private final RbacService rbacService;
    private final boolean rbacEnabled;

    public PlatformAuthorizationPolicy(
            RbacService rbacService,
            @Value("${agent-platform.security.rbac.enabled:false}") boolean rbacEnabled) {
        this.rbacService = rbacService;
        this.rbacEnabled = rbacEnabled;
    }

    @Override
    public void check(Principal principal, String action) {
        if (!rbacEnabled) {
            // 与 PermissionAspect 一致：未启用 RBAC 时注解/策略都完全放行
            return;
        }
        if (principal == null) {
            throw new IllegalStateException("启用 RBAC 时必须提供调用者身份（RunCommand.principal）");
        }
        Set<String> permissions = rbacService.permissionsOf(principal.tenantId(), principal.roles());
        if (!permissions.contains(action)) {
            throw new SecurityException("缺少权限：" + action);
        }
    }
}
