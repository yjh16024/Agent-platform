package com.agentplatform.framework.starter.springai;

import com.agentplatform.framework.runtime.SessionStore;
import com.agentplatform.framework.springai.ContextContributor;
import com.agentplatform.framework.springai.ContextInjectAdvisor;
import com.agentplatform.framework.springai.SessionChatMemoryRepository;
import com.agentplatform.framework.springai.SystemPromptGuardAdvisor;
import com.agentplatform.framework.starter.FrameworkProperties;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * E1 / E3 的自动装配（可选 starter）。
 *
 * <p>单独成模块的原因：基础 starter 不能依赖 spring-ai。
 * 一旦它的 Bean 方法签名里出现 Spring AI 类型，在类缺失时
 * {@code @ConditionalOnMissingBean} 的类型推导会抛
 * {@code NoClassDefFoundError}，应用**直接起不来**——
 * 「optional 依赖 + 条件装配」并不足以兜住这种情况。
 *
 * <p>默认开启，与平台改造期相反（那边默认关，为了可回滚），原因见
 * {@code docs/framework-plan.md} §5.3：同一份能力、两个受众，默认值本就该不同。
 */
@AutoConfiguration(after = com.agentplatform.framework.starter.FrameworkAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agent-platform.framework", name = "enabled", matchIfMissing = true)
public class FrameworkSpringAiAutoConfiguration {

    /** E1：把 ChatClient 的对话历史落到宿主的 SessionStore。 */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    @ConditionalOnProperty(prefix = "agent-platform.framework", name = "memory-enabled", matchIfMissing = true)
    public ChatMemoryRepository frameworkChatMemoryRepository(SessionStore store) {
        return new SessionChatMemoryRepository(store);
    }

    /** E3-1：长度守卫。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-platform.framework", name = "advisor-enabled", matchIfMissing = true)
    public SystemPromptGuardAdvisor systemPromptGuardAdvisor(FrameworkProperties properties) {
        return new SystemPromptGuardAdvisor(properties.guardMaxChars());
    }

    /** E3-2：默认不注入任何内容；宿主覆盖这个 Bean 即可接入画像 / 摘要 / 召回。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-platform.framework", name = "advisor-enabled", matchIfMissing = true)
    public ContextContributor contextContributor() {
        return (conversationId, userText) -> "";
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-platform.framework", name = "advisor-enabled", matchIfMissing = true)
    public ContextInjectAdvisor contextInjectAdvisor(ContextContributor contributor) {
        return new ContextInjectAdvisor(contributor);
    }
}
