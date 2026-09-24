mpackage com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审计过滤器（责任链一环）。
 * <p>记录每次工具调用的租户/工具名/入参摘要，供审计与诊断。</p>
 *
 * <p><b>为什么显式标 {@code @Order}</b>：不标的话它会落到
 * {@code Ordered.LOWEST_PRECEDENCE}，也就是排到所有带 {@code @Order} 的过滤器**之后** ——
 * 那样超时包装（{@link TimeoutToolFilter}）就会落到它外层，审计记不下"执行耗时"。
 * 责任链的顺序必须是**声明出来的**，不能依赖"没标就排前面"这种巧合。</p>
 */
@Slf4j
@Component
@Order(40)
public class AuditToolFilter implements ToolFilter {

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        log.info("[AUDIT] tool={} tenant={} args={}", invocation.toolName(),
                invocation.ctx().tenantId(), String.valueOf(invocation.args()));
        return chain.apply(invocation);
    }
}