package com.agentplatform.framework.api;

import java.util.Set;

/**
 * 调用者身份（B3：服务层鉴权出口的入参）。
 *
 * <p>框架不在服务层做鉴权决策，只负责**把身份带进来**并由
 * {@code AuthorizationPolicy} 决定放不放行。这样嵌入方直接调
 * {@link AgentRuntime#run} 时，鉴权链路不会因为「没走 Controller」而整体消失。
 */
public record Principal(String userId, String tenantId, Set<String> roles) {

    /** 未显式提供租户时的默认值，与平台现状保持一致。 */
    public static final String DEFAULT_TENANT = "default";

    /** 系统调用者：供定时任务、内部批处理使用，不代表任何真实用户。 */
    public static final Principal SYSTEM = new Principal("system", DEFAULT_TENANT, Set.of());

    public Principal {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填：匿名调用请显式使用 Principal.anonymous(tenantId)");
        }
        tenantId = (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT : tenantId;
        roles = (roles == null) ? Set.of() : Set.copyOf(roles);
    }

    public static Principal anonymous(String tenantId) {
        return new Principal("anonymous", tenantId, Set.of());
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }
}
