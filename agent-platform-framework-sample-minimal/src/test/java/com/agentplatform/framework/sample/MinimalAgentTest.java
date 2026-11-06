package com.agentplatform.framework.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 示例即验收：这条测试就是「10 分钟起步」门禁的执行体。
 *
 * <p>它跑在**没有 Spring 容器、没有数据库**的普通 JVM 上——
 * 这正是「框架可嵌入」的最小证明。
 */
class MinimalAgentTest {

    @Test
    void 无容器无数据库跑通一次带工具的对话() {
        assertEquals("现在是 12:00", MinimalAgent.run());
    }
}
