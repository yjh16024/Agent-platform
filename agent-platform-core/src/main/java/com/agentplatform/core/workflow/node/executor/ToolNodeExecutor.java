package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool 节点执行器。
 * <p>调用工具注册中心中的工具（内置/HTTP/MCP），经责任链执行。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolNodeExecutor implements NodeExecutor {

    private final ToolExecutor toolExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NodeType type() {
        return NodeType.Tool;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String toolName = (String) node.config().get("tool_name");
        if (toolName == null) {
            throw new IllegalArgumentException("Tool node " + node.id() + " requires config.tool_name");
        }
        // 解析入参（静态参数或 ${var} 引用）
        Map<String, Object> resolvedArgs = new LinkedHashMap<>();
        Object args = node.config().get("arguments");
        if (args instanceof Map<?, ?> map) {
            map.forEach((k, v) -> resolvedArgs.put(String.valueOf(k), resolveArg(v, ctx)));
        }
        JsonNode argsJson = mapper.valueToTree(resolvedArgs);
        ToolResult result = toolExecutor.run(toolName, argsJson, ToolContext.of("default", null, null));
        if (!result.success()) {
            return Map.of("ok", false, "error", result.error() == null ? "unknown" : result.error());
        }
        return Map.of("ok", true, "output", result.output());
    }

    private Object resolveArg(Object v, WorkflowContext ctx) {
        if (v instanceof String s && s.trim().matches("\\$\\{[^}]+}")) {
            return ctx.resolve(s.trim());
        }
        return v;
    }
}