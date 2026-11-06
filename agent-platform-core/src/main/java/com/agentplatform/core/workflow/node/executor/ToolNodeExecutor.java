package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolResult;
import com.agentplatform.framework.api.Tool;

import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
        String toolName = NodeConfigs.str(node, "tool_name", "toolName");
        if (NodeConfigs.blank(toolName)) {
            throw new IllegalArgumentException("Tool node " + node.id() + " requires config.tool_name");
        }
        /*
         * ★ 必须用 NodeConfigs.map，不能手写 `instanceof Map`（2026-10-12 修）。
         *
         * 画布上的「参数(JSON)」是一个**文本框**，`NODE_FIELDS` 里声明为 textarea，
         * 所以存进 `config.arguments` 的是**字符串**（`"{\"city\":\"北京\"}"`），
         * 不是 Map。原先这里只认 `instanceof Map`，于是：
         *   ⇒ **界面上填的参数被静默丢弃** —— 不报错、不提示，
         *     只是工具收到一个空对象，然后要么返回默认结果、要么报"缺少参数"，
         *     而用户看着界面上明明填了。
         *
         * `NodeConfigs.map` 同时支持两种形态（它的注释里就写着"支持 Map，或 JSON 字符串"），
         * 插件节点一直在用它 —— 两个同类型的执行器用两套解析方式，本身就是隐患。
         */
        Map<String, Object> resolvedArgs = new LinkedHashMap<>();
        NodeConfigs.map(node, "arguments", "args").forEach((k, v) ->
                // 值里可以嵌 `${变量}`；用 resolveString 而非"整值恰好是个变量"的判断，
                // 这样 "城市：${city}" 这种带前缀的写法也生效（PluginNodeExecutor 同此约定）
                resolvedArgs.put(k, v instanceof String s ? ctx.resolveString(s) : v));

        JsonNode argsJson = mapper.valueToTree(resolvedArgs);
        ToolResult result = toolExecutor.run(toolName, argsJson, ToolContext.of("default", null, null));
        if (!result.success()) {
            return Map.of("ok", false, "error", result.error() == null ? "unknown" : result.error());
        }
        return Map.of("ok", true, "output", result.output());
    }
}
