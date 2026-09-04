package com.agentplatform.core.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka 主题配置（条件装配）。
 * <p>
 * 声明事件总线主题，由 Spring Kafka {@code KafkaAdmin} 在启动时自动创建
 * （分区数 3，副本 1，适合单节点本地/开发集群）。
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "agent-platform.events.enabled", havingValue = "true")
public class KafkaTopicConfig {

    public static final String CONFIG_TOPIC = "agent-config-events";
    public static final String LOG_TOPIC = "log-events";

    @Bean
    public NewTopic agentConfigEventsTopic(@Value("${agent-platform.events.topics.config:agent-config-events}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }

    @Bean
    public NewTopic logEventsTopic(@Value("${agent-platform.events.topics.log:log-events}") String name) {
        return new NewTopic(name, 3, (short) 1);
    }
}