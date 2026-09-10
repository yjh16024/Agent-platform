package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具执行器（责任链：鉴权 → 校验 → 限流 → 审计 → 执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry registry;
    private final List<ToolFilter> filters;

    /**
     * 执行工具，经责任链。
     */
    public ToolResult run(String toolName, JsonNode args, ToolContext ctx) {
        Tool tool = registry.get(toolName);
        ToolInvocation invocation = new ToolInvocation(toolName, args, ctx);
        long start = System.currentTimeMillis();

        ToolResult result = new ToolChain(filters.stream().iterator(), tool)
                .apply(invocation);

        log.info("Tool {} executed in {}ms (success={})", toolName,
                System.currentTimeMillis() - start, result.success());
        return result;
    }
}