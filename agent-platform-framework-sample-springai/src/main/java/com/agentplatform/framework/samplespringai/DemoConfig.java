package com.agentplatform.framework.samplespringai;

import com.agentplatform.framework.springai.ContextContributor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主自己要写的全部内容：一个模型、一个上下文来源。
 *
 * <p>{@link ContextContributor} 会覆盖 starter 里的默认空实现
 * （那里是 {@code @ConditionalOnMissingBean}）——这正是「默认能跑、但宿主说了算」的体现。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfig {

    @Bean
    public ChatModel chatModel() {
        return new RecordingChatModel();
    }

    @Bean
    public ContextContributor demoContextContributor() {
        return (conversationId, userText) ->
                "【用户画像】该用户偏好简洁回答（会话 " + conversationId + "）";
    }
}
