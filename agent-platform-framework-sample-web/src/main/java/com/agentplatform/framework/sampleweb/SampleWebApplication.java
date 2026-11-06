package com.agentplatform.framework.sampleweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「加依赖即用」示例。
 *
 * <p>这个类里**没有任何框架配置**：没有 {@code @Import}、没有手写 Bean。
 * starter 的 {@code AutoConfiguration.imports} 会自己把会话存储、工具注册表、
 * E1 记忆仓储、E3 Advisor、以及 {@code AgentRuntime} 装配好——
 * 宿主只需要提供一个 {@link com.agentplatform.framework.api.ModelAdapter}。
 */
@SpringBootApplication
public class SampleWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleWebApplication.class, args);
    }
}
