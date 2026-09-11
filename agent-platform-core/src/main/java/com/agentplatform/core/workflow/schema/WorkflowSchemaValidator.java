package com.agentplatform.core.workflow.schema;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 校验节点结构合法性：ID 唯一、类型枚举合法、**类型必须有对应执行器**、Condition 分支必填、
 * 下游引用存在、无环（DAG 约束）。违规抛出统一异常并汇总错误。
 * </p>
 * <p>
 * 其中「类型必须有执行器」这一条把错误从**运行期提前到保存期**：
 * 画布（或任何调用方）若存了 {@code KnowledgeBase} 等尚未实现执行器的节点，保存时就会被拒绝，
 * 而不是等执行时才报 {@code No executor for node type}。
 * </p>
 */
@Component
public class WorkflowSchemaValidator {

    /**
     * 具备执行器的节点类型。
     * <p>无参构造时为**空集合**，表示不校验执行器可用性（单测直接 new 的场景）。</p>
     */
    private final Set<NodeType> executableTypes;

    /** 无参构造：不校验执行器（供单元测试直接实例化）。 */
    public WorkflowSchemaValidator() {
        this.executableTypes = Set.of();
    }

    /** Spring 注入实际存在的执行器，启用「类型必须有执行器」校验。 */
    @Autowired
    public WorkflowSchemaValidator(List<NodeExecutor> executors) {
        Set<NodeType> types = new HashSet<>();
        for (NodeExecutor e : executors) {
            types.add(e.type());
        }
        // DagEngine 中 End 复用 Start 的执行器，这里保持一致
        if (types.contains(NodeType.Start)) {
            types.add(NodeType.End);
        }
        this.executableTypes = types;
    }

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
            } else if (!executableTypes.isEmpty() && !executableTypes.contains(node.type())) {
                // 保存期拦截"尚无执行器"的类型，避免存出跑不通的图
                errors.add("Node " + node.id() + " type " + node.type() + " has no executor yet");
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