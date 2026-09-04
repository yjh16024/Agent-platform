package com.agentplatform.core.events;

import com.agentplatform.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 事件总线实现（条件装配，默认关闭）。
 * <p>
 * 异步发送（non-blocking），broker 不可用时静默降级，不阻断主业务流程。
 * 主题通过 {@link KafkaTopicConfig} 声明并随 Admin 自动创建。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.events.enabled", havingValue = "true")
public class KafkaEventBus implements EventBus {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void publish(String topic, String key, Object payload) {
        try {
            String json = payload instanceof String s ? s : JsonUtils.toJson(payload);
            kafkaTemplate.send(topic, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.debug("Kafka publish to {} failed: {}", topic, ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.debug("Kafka event bus unavailable ({}): {}", topic, e.getMessage());
        }
    }
}