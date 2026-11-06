package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolResult;
import tools.jackson.databind.JsonNode;

/**
 * 示例工具：返回固定时间。
 *
 * <p>真实的工具会读系统时钟或调外部 API —— 这里固定值是为了让 sample 的输出可断言。
 * 注意它拿得到 {@link ToolContext}（租户 / 智能体 / runId / sessionId），
 * 这正是 B3「鉴权要有服务层出口」的落点。
 */
public class ClockTool implements Tool {

    @Override
    public String name() {
        return "clock";
    }

    @Override
    public String description() {
        return "返回当前时间（示例实现固定返回 12:00）";
    }

    @Override
    public JsonNode inputSchema() {
        return null;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        return ToolResult.ok("12:00");
    }
}
