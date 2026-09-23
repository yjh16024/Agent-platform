package com.agentplatform.plugin.sdk.model;

import java.util.Set;

/**
 * 领域事件类型清单 —— **整个事件体系的唯一权威来源**。
 *
 * <h3>为什么单独用一个常量类而不是散在字符串里</h3>
 * 事件类型是<b>插件契约</b>：一旦有插件订阅了某个类型，改动它就会破坏插件。
 * 与 {@code RbacPermission} 的取舍一致 —— 把可用值收在一处，写错在评审时就能发现，
 * 而不是上线后表现为"插件订阅了却从没被触发"。
 *
 * <h3>⚠️ 契约稳定性约定</h3>
 * <ul>
 *   <li><b>类型名与 payload 里的 key 都算契约</b>：只增不改；确实要改就新增一个类型，
 *       旧类型保留一段时间再废弃；</li>
 *   <li><b>payload 保持浅层</b>（值用 String / Number / Boolean / 简单集合），
 *       不要塞实体对象 —— 那等于把核心领域模型的内部结构变成了公开契约；</li>
 *   <li><b>只登记"确定会有订阅方"的事件</b>。像 {@code agent.run.started} 这种
 *       目前没人需要的事件不要预留 —— 空事件类型也是一种需要维护的契约。</li>
 * </ul>
 *
 * <h3>为什么分「agent 级」与「租户级」</h3>
 * 插件能力是<b>按智能体隔离</b>的（挂到 A 的插件只对 A 生效）。而有些事件天生没有
 * agent 维度（配额是租户级的、权限拒绝可能发生在任何接口上）。若把这类事件也派发给
 * 插件订阅者，就<b>打破了隔离模型</b> —— 等于任何一台智能体上的插件都能监听到全租户的行为。
 * <p>所以约定：<b>只有带 {@code agentId} 的事件才派发给插件</b>（见 {@link #PLUGIN_SUBSCRIBABLE}）；
 * 租户级事件只供核心模块使用，将来若要支持"平台级订阅者"再单独设计，而不是现在偷偷放开。</p>
 */
public final class EventTypes {

    // ---------------- agent 级（可被插件订阅） ----------------

    /** 智能体一次运行成功完成。 */
    public static final String AGENT_RUN_COMPLETED = "agent.run.completed";

    /** 智能体一次运行失败（LLM 调用抛异常且未被插件兜底）。 */
    public static final String AGENT_RUN_FAILED = "agent.run.failed";

    // ---------------- 租户级（不派发给插件） ----------------

    /** 配额超限。无 agent 维度 —— 是"谁把业务撞到限额上"，不是"哪台智能体的事"。 */
    public static final String QUOTA_EXCEEDED = "quota.exceeded";

    /** 权限被拒。同样无 agent 维度。 */
    public static final String PERMISSION_DENIED = "permission.denied";

    /**
     * 可被插件订阅的事件类型集合。
     *
     * <p>宿主只把<b>这个集合内 + 带 agentId</b> 的事件派发给插件订阅者；其它事件即使
     * 插件声明订阅了也不会收到（注册时会记一条 warn，便于作者发现自己理解错了）。</p>
     */
    public static final Set<String> PLUGIN_SUBSCRIBABLE = Set.of(
            AGENT_RUN_COMPLETED,
            AGENT_RUN_FAILED);

    /** 事件是否为 agent 级（能否按智能体隔离派发给插件）。 */
    public static boolean isPluginSubscribable(String type) {
        return type != null && PLUGIN_SUBSCRIBABLE.contains(type);
    }

    private EventTypes() {
    }
}
