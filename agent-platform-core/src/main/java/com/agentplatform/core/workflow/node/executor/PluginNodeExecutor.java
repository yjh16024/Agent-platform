package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件节点执行器（画布「插件」节点）。
 * <p>
 * 插件贡献的工具由 {@code PluginToolAdapter} 注册进 {@code ToolRegistry}，因此这里与普通工具节点
 * 走同一条执行路径（{@link ToolExecutor} 责任链：鉴权 → 校验 → 限流 → 审计 → 执行），
 * 从而天然支持「插件热插拔」——插件 detach 后工具消失，节点会在执行时报"工具不存在"。
 * </p>
 * 配置项：{@code tool_name}（插件工具名，必填）、{@code arguments}（Map 或 JSON，支持 {@code ${var}}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginNodeExecutor implements NodeExecutor {

    private final ToolExecutor toolExecutor;

    @Override
    public NodeType type() {
        return NodeType.Plugin;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String toolName = NodeConfigs.str(node, "tool_name", "toolName");
        if (NodeConfigs.blank(toolName)) {
            throw new IllegalArgumentException("Plugin node " + node.id() + " requires config.tool_name");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        NodeConfigs.map(node, "arguments", "args").forEach((k, v) -> resolved.put(k,
                v instanceof String s ? ctx.resolveString(s) : v));

        JsonNode argsJson = com.agentplatform.common.util.JsonUtils.mapper().valueToTree(resolved);
        ToolResult result = toolExecutor.run(toolName, argsJson, ToolContext.of("default", null, null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.success());
        if (result.success()) {
            out.put("output", result.output());
        } else {
            out.put("error", result.error() == null ? "unknown" : result.error());
        }
        log.debug("Plugin node {} executed tool={} success={}", node.id(), toolName, result.success());
        return out;
    }
}
