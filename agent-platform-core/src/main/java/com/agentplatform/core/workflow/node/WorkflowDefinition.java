package com.agentplatform.core.workflow.node;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 完整工作流定义（DAG）。
 *
 * @param name      流程名
 * @param nodes     节点列表
 * @param entryNode 入口节点 ID（默认取 Start 节点）
 * @param variables 初始变量
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(
        String name,
        List<WorkflowNode> nodes,
        String entryNode,
        Map<String, Object> variables
) {
    /**
     * 按 ID 查找节点。
     */
    public WorkflowNode node(String id) {
        return nodes.stream()
                .filter(n -> id.equals(n.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找入口节点（显式指定 or Start 节点 or 首个节点）。
     */
    public WorkflowNode entry() {
        if (entryNode != null) {
            return node(entryNode);
        }
        return nodes.stream()
                .filter(n -> n.type() == NodeType.Start)
                .findFirst()
                .orElseGet(() -> nodes.isEmpty() ? null : nodes.get(0));
    }
}