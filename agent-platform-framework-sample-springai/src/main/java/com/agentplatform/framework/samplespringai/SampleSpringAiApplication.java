package com.agentplatform.framework.samplespringai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「真外部集成」示例：这是一个**普通 Spring AI 项目**。
 *
 * <p>它没有 {@code @Import}、没有手写装配、没有引用框架的运行时 API，
 * 只做两件 Spring AI 项目本来就会做的事：声明一个 {@code ChatModel}、用 {@code ChatClient} 对话。
 * 加一个 starter 依赖之后，它额外获得：</p>
 * <ul>
 *   <li><b>E1</b>：{@code MessageWindowChatMemory} 的历史落到框架的 {@code SessionStore}，重启不丢；</li>
 *   <li><b>E3</b>：系统提示词长度守卫 + 上下文注入（画像 / 摘要 / 召回由宿主提供）。</li>
 * </ul>
 */
@SpringBootApplication
public class SampleSpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSpringAiApplication.class, args);
    }
}
