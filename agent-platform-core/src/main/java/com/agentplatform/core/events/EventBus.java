package com.agentplatform.core.events;

/**
 * 事件总线抽象。
 * <p>
 * 用于「配置变更事件广播 / 日志事件流」等跨组件事件分发。默认无实现（不发射），
 * 生产接入 {@code KafkaEventBus}（经 {@code agent-platform.events.enabled=true} 启用），
 * 保证无 broker 场景（本地开发 / 单测）完全静默。
 * </p>
 */
public interface EventBus {

    /**
     * 发布一条事件。
     *
     * @param topic   主题
     * @param key     分区键（可空）
     * @param payload 事件体（对象或 JSON 字符串）
     */
    void publish(String topic, String key, Object payload);

    /**
     * 发布一条事件（无分区键）。
     */
    default void publish(String topic, Object payload) {
        publish(topic, null, payload);
    }
}