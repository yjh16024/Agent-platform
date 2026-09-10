package com.agentplatform.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 核心调度层应用入口。
 * <p>
 * 承载 Agent Runtime、会话管理、模型适配、RAG、工具、工作流、
 * 插件运行时、日志诊断、提示词优化等核心能力。
 * </p>
 * <p>
 * Spring Boot 4 起 {@code @EntityScan} 迁至 {@code spring-boot-persistence} 模块
 * （包名 {@code org.springframework.boot.persistence.autoconfigure}）。
 * JDK 21 虚拟线程由 Spring Boot 默认开启（Tomcat），
 * 自定义异步任务使用 {@code newVirtualThreadPerTaskExecutor}。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.agentplatform")
@EntityScan(basePackages = "com.agentplatform.model")
@EnableJpaRepositories(basePackages = "com.agentplatform.model")
@EnableAsync
@EnableScheduling
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}