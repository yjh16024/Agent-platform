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
 *
 * <h3>2026-09-24 补的"必填 config"（把执行期异常提前到保存期）</h3>
 * LLM 少了 {@code prompt}、KB 少了 {@code kb_ids}/{@code query}、工具/插件少了 {@code tool_name}、
 * Agent 少了 {@code agent_id} 这类，过去要等执行到那个节点才报
 * {@code IllegalArgumentException("... requires config.xxx")}，现在保存时就拒绝。
 * 清单来自各 {@code *NodeExecutor} 里那些 throw 分支，见 {@link #REQUIRED_CONFIG}。
 *
 * <p><b>⚠️ 曾经还想加两条、实测后放弃，记在这里避免以后重复踩：</b></p>
 * <ol>
 *   <li><b>{@code output_var} 重名</b> —— 放弃。**互斥分支写同名变量是合法且自然的**
 *       （"不管走哪个分支，结果都叫 {@code branch}"），而"两个节点是否互斥"在静态期
 *       无法判定。硬查会把这类正确工作流拦下来。</li>
 *   <li><b>变量引用必须可解析</b>（{@code ${foo}} 的 {@code foo} 得有人定义）—— 放弃。
 *       变量的来源除了「{@code def.variables()} 初始变量」和「节点 {@code output_var}」，
 *       还有**运行时传入的 input Map**（{@code DagEngine} 里 {@code new WorkflowContext(input)}），
 *       静态期**根本不知道**里面有哪些 key。实测会把 {@code DagEngineTest} 里
 *       {@code ${x}} / {@code ${user}} 这类完全合法的引用误判为未定义。</li>
 * </ol>
 * <p>这两条如果要抓，只能放到**运行期**（变量解析失败时给出带节点 id 的明确报错），
 * 而不是保存期。</p>
 */
@Component
public class WorkflowSchemaValidator {

    /**
     * 各类型节点的**必填** config 键。
     * <p>键名按 snake_case 写，判定时同时接受 camelCase（历史原因，画布早期用过 camelCase）。</p>
     * <p>取值依据：各 {@code *NodeExecutor} 里 {@code throw new IllegalArgumentException("... requires config.xxx")}
     * 的那些分支 —— 这里列的就是它们。</p>
     */
    private static final Map<NodeType, List<String>> REQUIRED_CONFIG = Map.of(
            NodeType.LLM, List.of("prompt"),
            NodeType.KnowledgeBase, List.of("kb_ids", "query"),
            NodeType.Tool, List.of("tool_name"),
            NodeType.Plugin, List.of("tool_name"),
            NodeType.Skill, List.of("skill_id"),
            NodeType.Agent, List.of("agent_id"),
            NodeType.Http, List.of("url"),
            // Loop 的循环体入口。它**不能**用 next/branches 表达（那会形成环、被 hasCycle 拒掉），
            // 所以写在 config 里 —— 也正因此它不在"下游引用存在性"那段的检查范围内，
            // 需要在下面单独校验（见 validateLoopBody）。
            NodeType.Loop, List.of("loop_body"));

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

        // Loop 的循环体入口必须指向存在的节点。
        // 它写在 config.loop_body 里（不是 next/branches），所以上面那段"下游引用存在性"覆盖不到 ——
        // 若这里不查，写错的 loop_body 要等运行到该节点才报错。
        for (WorkflowNode node : def.nodes()) {
            if (node.type() != NodeType.Loop) {
                continue;
            }
            String body = loopBodyOf(node);
            if (body != null && !byId.containsKey(body)) {
                errors.add("Loop node " + node.id() + " references non-existent loop_body: " + body);
            }
        }

        // 必须有 End 节点（或至少一个终结节点）
        boolean hasEnd = def.nodes().stream().anyMatch(n -> n.type() == NodeType.End || n.nextIds().isEmpty());
        if (!hasEnd) {
            errors.add("Workflow missing terminal node (End or node without next)");
        }

        // 环检测。
        // ⚠️ 循环体**刻意不参与**环检测：它的入口写在 config.loop_body 里，而 hasCycle 只看
        // next/branches。这是设计而非疏漏 —— 若把 loop_body 也当边，任何合法的循环都会变成"环"而被拒。
        // Loop 的重复执行由 DagEngine 用"每轮独立的环保护集合"实现，不依赖图上的环。
        if (hasCycle(def)) {
            errors.add("Workflow contains a cycle; DAG required");
        }

        // ---- 必填 config（把执行期的 IllegalArgumentException 提前到保存期）----
        for (WorkflowNode node : def.nodes()) {
            List<String> required = REQUIRED_CONFIG.get(node.type());
            if (required == null) {
                continue;
            }
            for (String key : required) {
                if (!hasConfigKey(node.config(), key)) {
                    errors.add("Node " + node.id() + " (" + node.type() + ") requires config." + key);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw BizException.validation("Workflow validation failed: " + String.join("; ", errors));
        }
    }

    /**
     * config 里是否存在某个键（snake_case 或等价的 camelCase）。
     */
    private static boolean hasConfigKey(Map<String, Object> config, String snakeKey) {
        if (config == null) {
            return false;
        }
        if (config.containsKey(snakeKey)) {
            return true;
        }
        return config.containsKey(toCamel(snakeKey));
    }

    /**
     * 取 Loop 节点的循环体入口（兼容 snake_case 与 camelCase）。
     * <p>与 {@code NodeConfigs.str()} 同类，但那个类在 {@code node.executor} 包内是包私有的，
     * 本类跨包用不了，故此处只取这一个键、就地实现（不做通用化，避免为一个键扩大可见性）。</p>
     */
    private static String loopBodyOf(WorkflowNode node) {
        Map<String, Object> config = node.config();
        if (config == null) {
            return null;
        }
        Object value = config.get("loop_body");
        if (value == null) {
            value = config.get("loopBody");
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** {@code kb_ids} → {@code kbIds}。 */
    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
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