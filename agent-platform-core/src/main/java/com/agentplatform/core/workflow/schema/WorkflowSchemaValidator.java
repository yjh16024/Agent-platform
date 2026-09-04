package com.agentplatform.core.workflow.schema;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流 Schema 校验器。
 * <p>
 * 校验节点结构合法性：ID 唯一、类型枚举合法、Condition 分支必填、
 * 下游引用存在、无环（DAG 约束）。违规抛出统一异常并汇总错误。
 * </p>
 */
@Component
public class WorkflowSchemaValidator {

    /**
     * 校验工作流定义，违规抛异常。
     */
    public void validate(WorkflowDefinition def) {
        List<String> errors = new ArrayList<>();

        if (def == null || def.nodes() == null || def.nodes().isEmpty()) {
            throw BizException.validation("Workflow must contain at least one node");
        }

        Set<String> ids = new HashSet<>();
        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode node : def.nodes()) {
            // ID 唯一
            if (node.id() == null || node.id().isBlank()) {
                errors.add("Node id is required");
                continue;
            }
            if (!ids.add(node.id())) {
                errors.add("Duplicate node id: " + node.id());
            }
            byId.put(node.id(), node);

            // 类型合法性（NodeType 枚举已保证，此处检查 null）
            if (node.type() == null) {
                errors.add("Node " + node.id() + " missing type");
            }

            // Condition 节点必须有 branches
            if (node.type() == NodeType.Condition && (node.branches() == null || node.branches().isEmpty())) {
                errors.add("Condition node " + node.id() + " requires branches");
            }
        }

        // 下游引用存在性 + 分支目标存在性
        for (WorkflowNode node : def.nodes()) {
            for (String nextId : node.nextIds()) {
                if (!byId.containsKey(nextId)) {
                    errors.add("Node " + node.id() + " references non-existent node: " + nextId);
                }
            }
            if (node.branches() != null) {
                for (WorkflowNode.Branch b : node.branches()) {
                    if (b.target() == null || !byId.containsKey(b.target())) {
                        errors.add("Branch of " + node.id() + " references non-existent target: " + b.target());
                    }
                }
            }
        }

        // 必须有 End 节点（或至少一个终结节点）
        boolean hasEnd = def.nodes().stream().anyMatch(n -> n.type() == NodeType.End || n.nextIds().isEmpty());
        if (!hasEnd) {
            errors.add("Workflow missing terminal node (End or node without next)");
        }

        // 环检测
        if (hasCycle(def)) {
            errors.add("Workflow contains a cycle; DAG required");
        }

        if (!errors.isEmpty()) {
            throw BizException.validation("Workflow validation failed: " + String.join("; ", errors));
        }
    }

    /**
     * DFS 环检测。
     */
    private boolean hasCycle(WorkflowDefinition def) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (WorkflowNode node : def.nodes()) {
            if (dfs(node.id(), def, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfs(String id, WorkflowDefinition def, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            return true; // 环
        }
        if (visited.contains(id)) {
            return false;
        }
        WorkflowNode node = def.node(id);
        if (node == null) {
            return false;
        }
        visiting.add(id);
        for (String nextId : node.nextIds()) {
            if (dfs(nextId, def, visiting, visited)) {
                return true;
            }
        }
        // Condition 分支目标也视为边
        if (node.branches() != null) {
            for (WorkflowNode.Branch b : node.branches()) {
                if (dfs(b.target(), def, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }
}