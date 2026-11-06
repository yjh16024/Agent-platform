package com.agentplatform.framework.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 框架装配开关。
 *
 * <p>默认值遵循「加依赖就能跑」：全部能力默认开启，宿主想关再关。
 * 这与**平台改造期**的默认值相反（那里为了可回滚默认关闭，见 docs/framework-plan.md §5.3）——
 * 同一份能力、两个受众，默认值本来就该不同。
 *
 * @param enabled       总开关
 * @param memoryEnabled E1：把对话历史交给 SessionStore
 * @param advisorEnabled E3：长度守卫 + 上下文注入
 * @param guardMaxChars 守卫预算（字符数）
 * @param maxRounds     工具循环默认轮数上限
 * @param defaultModel  请求未指定模型时使用
 * @param systemPrompt  未显式提供时的系统提示词
 */
@ConfigurationProperties(prefix = "agent-platform.framework")
public record FrameworkProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean memoryEnabled,
        @DefaultValue("true") boolean advisorEnabled,
        @DefaultValue("8000") int guardMaxChars,
        @DefaultValue("5") int maxRounds,
        @DefaultValue("framework-default") String defaultModel,
        @DefaultValue("你是一个乐于助人的智能体。") String systemPrompt) {
}
