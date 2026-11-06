package com.agentplatform.framework.api;

/**
 * 授权策略（B3：服务层的鉴权出口）。
 *
 * <p>框架不在服务层做权限判断，只负责**把调用者身份带进来**并把决策权交出去。
 * 这样嵌入方直接调 {@link AgentRuntime#run} 时，鉴权不会因为「没走 Controller」而整体消失
 * —— 那是平台原实现最危险的盲区。</p>
 *
 * <p>默认实现 {@link #PERMISSIVE} 放行一切，用于单租户 / 单机场景；
 * 多租户系统应当注入自己的实现（平台侧是 {@code PlatformAuthorizationPolicy}）。</p>
 */
@FunctionalInterface
public interface AuthorizationPolicy {

    /** 放行一切：默认值，也是「没有权限系统」时的正确行为。 */
    AuthorizationPolicy PERMISSIVE = (principal, action) -> {
    };

    /**
     * 校验调用者是否可执行该动作。
     *
     * @param principal 调用者身份；可能为 null（调用方未提供）
     * @param action    动作码，见 {@link Actions}
     * @throws RuntimeException 拒绝时抛出（实现方决定类型，框架原样向上抛）
     */
    void check(Principal principal, String action);

    /** 动作码常量：与平台 RBAC 的权限码保持一致。 */
    final class Actions {

        /** 发起一次智能体运行。 */
        public static final String AGENT_INVOKE = "agent:invoke";

        /** 调用一次工具（工具级授权由工具责任链负责，这里只留给需要前置判断的场景）。 */
        public static final String TOOL_CALL = "tool:call";

        private Actions() {
        }
    }
}
