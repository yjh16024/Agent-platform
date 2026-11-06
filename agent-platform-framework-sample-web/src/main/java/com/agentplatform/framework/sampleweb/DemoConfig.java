package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主要写的**全部**代码：一个模型适配器、一个工具。
 *
 * <p>注意这里没有 {@code AgentRuntime} 的创建代码——那是 starter 的事。
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfig {

    @Bean
    public ModelAdapter demoToolModel() {
        return new DemoToolModel();
    }

    @Bean
    public Tool clockTool() {
        return new ClockTool();
    }
}
