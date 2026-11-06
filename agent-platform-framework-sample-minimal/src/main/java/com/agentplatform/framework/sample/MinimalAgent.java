package com.agentplatform.framework.sample;

import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.ToolOptions;
import com.agentplatform.framework.runtime.DefaultAgentRuntime;

/**
 * 最小可运行示例：**没有 Spring 容器、没有数据库、没有配置文件**。
 *
 * <p>跑法：{@code mvn -pl agent-platform-framework-sample-minimal -am exec:java
 * -Dexec.mainClass=com.agentplatform.framework.sample.MinimalAgent}
 */
public final class MinimalAgent {

    private MinimalAgent() {
    }

    public static void main(String[] args) {
        System.out.println(run());
    }

    /** 返回本轮回答，供 sample 的测试断言使用。 */
    public static String run() {
        AgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(new DemoToolModel())      // 换成任意 ModelAdapter 即可接真实模型
                .tool(new ClockTool())             // 注册一个工具
                .build();

        RunResult result = runtime.run(RunCommand.builder("demo-agent")
                .text("现在几点了？")
                .session("demo-session")
                .tenant("demo-tenant")
                .tools(ToolOptions.all(3))
                .build());

        return result.reply();
    }
}
