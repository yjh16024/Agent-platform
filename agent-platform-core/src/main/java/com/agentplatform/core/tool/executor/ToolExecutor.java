package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
     * 工具调用记录收集器（前端「工具调用可视化」的数据来源）。
     *
     * <p><b>为什么挂在这里</b>：平台有两条工具执行路径 —— 自研适配器的
     * {@code runToolLoop} 与 Spring AI 的原生 tool-role 循环（内层、返回值要穿过
     * {@code ChatResponse}）。它们的**唯一汇聚点**就是本方法，所以在这一处记一笔，
     * 两条路径就都覆盖了，且不必改动任何适配器。详见 {@link ToolCallCollector}。</p>
     *
     * <p>用字段注入而非构造参数：本类被单元测试以 {@code new ToolExecutor(registry, filters)}
     * 构造，加构造参数会让那些测试全部要改；而收集器**缺失时功能退化为"没有记录"**，
     * 不该因此让执行本身失败。</p>
     */
    @Autowired(required = false)
    private ToolCallCollector toolCallCollector;

    /**
     * 执行工具，经责任链。
     */
    public ToolResult run(String toolName, JsonNode args, ToolContext ctx) {
        Tool tool = registry.get(toolName);
        ToolInvocation invocation = new ToolInvocation(toolName, args, ctx);
        long start = System.currentTimeMillis();

        ToolResult result = new ToolChain(filters.stream().iterator(), tool)
                .apply(invocation);

        long latencyMs = System.currentTimeMillis() - start;
        log.info("Tool {} executed in {}ms (success={})", toolName, latencyMs, result.success());
        recordCall(toolName, args, ctx, result, latencyMs);
        return result;
    }

    /**
     * 记一笔调用（供前端展示）。
     *
     * <p>记录失败**绝不影响工具执行** —— 它只是可观测性，不是主流程的一部分。
     * 非运行链路（工作流节点、连通性测试）没有 {@code runId}，收集器会静默忽略。</p>
     */
    private void recordCall(String toolName, JsonNode args, ToolContext ctx,
                            ToolResult result, long latencyMs) {
        if (toolCallCollector == null || ctx == null) {
            return;
        }
        try {
            toolCallCollector.record(ctx.runId(),
                    ToolCallRecord.of(toolName, args, result, latencyMs));
        } catch (Exception e) {
            log.debug("[tool] 记录工具调用失败（忽略）：{}", e.getMessage());
        }
    }
}