package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;

/**
 * 工具责任链过滤器（责任链模式）。
 * <p>顺序：鉴权 → 参数校验 → 限流 → 审计 → 执行。</p>
 */
public interface ToolFilter {

    /**
     * 执行过滤逻辑并传递给下一环。
     */
    ToolResult doFilter(ToolInvocation invocation, ToolChain chain);
}