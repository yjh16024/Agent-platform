package com.agentplatform.framework.starter;

import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.runtime.SessionStore;
import com.agentplatform.framework.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基础 starter 的装配测试。
 *
 * <p>关键点：本模块的 classpath 上**没有 Spring AI**。
 * 这条测试能通过，就说明「框架本体不依赖 Spring AI」不是口号而是结构事实。
 */
class FrameworkAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FrameworkAutoConfiguration.class));

    @Test
    void 没有模型适配器时不创建运行时() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AgentRuntime.class));
    }

    @Test
    void 提供适配器后自动装配运行时与会话存储() {
        runner.withBean(ModelAdapter.class, StubAdapter::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(SessionStore.class);
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                });
    }

    @Test
    void 总开关关闭时什么都不装配() {
        runner.withPropertyValues("agent-platform.framework.enabled=false")
                .withBean(ModelAdapter.class, StubAdapter::new)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentRuntime.class);
                    assertThat(context).doesNotHaveBean(SessionStore.class);
                });
    }

    private static class StubAdapter implements ModelAdapter {

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.TEXT);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return new ChatResponse("ok", 1, 1, 0.0, 1L);
        }

        @Override
        public Flux<ChatDelta> stream(ChatRequest request) {
            return Flux.empty();
        }
    }
}
