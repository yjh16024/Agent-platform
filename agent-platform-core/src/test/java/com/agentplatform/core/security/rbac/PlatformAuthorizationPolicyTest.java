package com.agentplatform.core.security.rbac;

import com.agentplatform.framework.api.AuthorizationPolicy;
import com.agentplatform.framework.api.Principal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 鉴权接缝：框架策略 → 平台 RBAC。
 */
class PlatformAuthorizationPolicyTest {

    private final RbacService rbac = mock(RbacService.class);
    private final Principal caller = new Principal("u-1", "t-1", Set.of("operator"));

    @Test
    void 未启用RBAC时一律放行且不查库() {
        PlatformAuthorizationPolicy policy = new PlatformAuthorizationPolicy(rbac, false);

        assertDoesNotThrow(() -> policy.check(null, AuthorizationPolicy.Actions.AGENT_INVOKE));
    }

    @Test
    void 启用RBAC但没给身份时failClosed() {
        PlatformAuthorizationPolicy policy = new PlatformAuthorizationPolicy(rbac, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> policy.check(null, AuthorizationPolicy.Actions.AGENT_INVOKE));
        assertTrue(ex.getMessage().contains("principal"));
    }

    @Test
    void 有权限则放行() {
        when(rbac.permissionsOf("t-1", caller.roles()))
                .thenReturn(Set.of(AuthorizationPolicy.Actions.AGENT_INVOKE));
        PlatformAuthorizationPolicy policy = new PlatformAuthorizationPolicy(rbac, true);

        assertDoesNotThrow(() -> policy.check(caller, AuthorizationPolicy.Actions.AGENT_INVOKE));
    }

    @Test
    void 缺权限应拒绝并带出权限码() {
        when(rbac.permissionsOf("t-1", caller.roles())).thenReturn(Set.of("agent:read"));
        PlatformAuthorizationPolicy policy = new PlatformAuthorizationPolicy(rbac, true);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> policy.check(caller, AuthorizationPolicy.Actions.AGENT_INVOKE));
        assertTrue(ex.getMessage().contains("agent:invoke"));
    }
}
