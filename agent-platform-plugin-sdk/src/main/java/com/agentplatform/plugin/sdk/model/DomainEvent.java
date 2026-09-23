package com.agentplatform.plugin.sdk.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 领域事件 —— 系统里"发生了一件事"的统一载体。
 *
 * <h3>与「运行日志」「操作审计」的区别</h3>
 * 三者都描述"发生了什么"，但消费者与用途完全不同：
 * <ul>
 *   <li>{@code LogService}：给<b>人</b>看，用于排障（"这次调用为什么慢"）；</li>
 *   <li>{@code AuditService}：给<b>人</b>看，用于追责（"谁改了这个智能体"）；</li>
 *   <li><b>本类</b>：给<b>代码</b>用，用于触发动作（"运行失败了 → 通知/上报/重试/统计"）。</li>
 * </ul>
 * 前两者是"事后查阅"，本类是"实时驱动"。所以事件<b>不负责持久化</b>：
 * 宿主只在内存里分发给订阅者，需要留痕的订阅者自己决定写哪里。
 *
 * <h3>payload 的稳定性约定</h3>
 * <p><b>只有 key 是契约</b>：订阅者只应读自己需要的、且文档里声明过的 key；
 * 宿主可能在不通知的情况下增加新 key（属于兼容变更）。反过来，
 * <b>删除或改名 key 是破坏性变更</b>，必须走"新增事件类型"的路子。</p>
 *
 * @param type       事件类型，取值见 {@link EventTypes}
 * @param tenantId   所属租户
 * @param agentId    <b>可空</b>。为空表示这是租户级事件，<b>不会派发给插件订阅者</b>
 *                   （详见 {@link EventTypes} 里关于隔离的说明）
 * @param runId      可空。一次运行关联 ID，便于订阅者把多个事件串起来
 * @param occurredAt 发生时间
 * @param payload    事件负载。<b>保持浅层</b>（String / Number / Boolean / 简单集合），
 *                   不要塞实体对象 —— 那会把核心领域模型的内部结构变成公开契约
 */
public record DomainEvent(
        String type,
        String tenantId,
        String agentId,
        String runId,
        LocalDateTime occurredAt,
        Map<String, Object> payload
) {

    public DomainEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
        // 防御性拷贝：调用方后续改动原 Map 不应影响已发布的事件
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** agent 级事件（会被派发给插件订阅者）。 */
    public static DomainEvent ofAgent(String type, String tenantId, String agentId,
                                      String runId, Map<String, Object> payload) {
        return new DomainEvent(type, tenantId, agentId, runId, LocalDateTime.now(), payload);
    }

    /**
     * 租户级事件（**不**派发给插件 —— 没有 agentId）。
     */
    public static DomainEvent ofTenant(String type, String tenantId, Map<String, Object> payload) {
        return new DomainEvent(type, tenantId, null, null, LocalDateTime.now(), payload);
    }

    /** 是否 agent 级（决定能否按智能体隔离派发给插件）。 */
    public boolean isAgentScoped() {
        return agentId != null && !agentId.isBlank();
    }

    /** 便捷读取：按 key 取字符串，缺失返回 null。 */
    public String string(String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 便捷读取：按 key 取 long，缺失或不可解析返回 defaultValue。 */
    public long number(String key, long defaultValue) {
        Object v = payload.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? defaultValue : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 构造负载的便捷入口（链式，可读性优先）。 */
    public static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
