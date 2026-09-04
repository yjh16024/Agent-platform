package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transform 节点执行器（变量转换）。
 * <p>
 * 将 input_mapping 中的变量引用（${var.path}）解析为实际值输出，
 * 用于类型转换、字段重命名、默认值注入等。
 * </p>
 */
@Component
public class TransformNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.Transform;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        if (node.inputMapping() == null) {
            return node.config();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : node.inputMapping().entrySet()) {
            result.put(e.getKey(), resolveValue(e.getValue(), ctx));
        }
        return result;
    }

    private Object resolveValue(Object value, WorkflowContext ctx) {
        if (value instanceof String s) {
            // 纯 ${var} → 返回实际值；含字面量 → 字符串替换
            String trimmed = s.trim();
            if (trimmed.matches("\\$\\{[^}]+}")) {
                return ctx.resolve(trimmed);
            }
            return ctx.resolveString(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveValue(v, ctx)));
            return resolved;
        }
        return value;
    }
}