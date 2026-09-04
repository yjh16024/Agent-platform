package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计过滤器（责任链一环）。
 * <p>记录每次工具调用的租户/工具名/入参摘要，供审计与诊断。</p>
 */
@Slf4j
@Component
public class AuditToolFilter implements ToolFilter {

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        log.info("[AUDIT] tool={} tenant={} args={}", invocation.toolName(),
                invocation.ctx().tenantId(), String.valueOf(invocation.args()));
        return chain.apply(invocation);
    }
}