package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;

/**
 * 工具责任链（责任链模式）。
 * <p>按过滤器迭代驱动，链尾执行工具本身。拦截点：鉴权 → 校验 → 限流 → 审计 → 执行。</p>
 */
@Slf4j
public class ToolChain {

    private final Iterator<ToolFilter> filters;
    private final Tool target;

    public ToolChain(Iterator<ToolFilter> filters, Tool target) {
        this.filters = filters;
        this.target = target;
    }

    /**
     * 驱动责任链：有过滤器则逐个执行，链尾调用工具。
     */
    public ToolResult apply(ToolInvocation invocation) {
        if (filters.hasNext()) {
            return filters.next().doFilter(invocation, this);
        }
        try {
            return target.execute(invocation.args(), invocation.ctx());
        } catch (Exception e) {
            log.error("Tool execution error: {}", e.getMessage(), e);
            return ToolResult.fail(e.getMessage());
        }
    }
}