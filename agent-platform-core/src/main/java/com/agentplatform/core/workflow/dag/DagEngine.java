package com.agentplatform.core.workflow.dag;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自研轻量 DAG 引擎（单次请求内内存编排）。
 * <p>
 * 承担高性能短链路编排：运行时按依赖遍历节点，Condition 动态分支，
 * 多下游并行执行（虚拟线程）；长流程由 Temporal 承担（双层互补）。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>策略分发：NodeExecutor 按类型分发（Spring 自动收集）</li>
 *   <li>变量作用域：节点 output_var 写入 WorkflowContext，下游 ${var.path} 引用</li>
 *   <li>并行：虚拟线程 Executor 执行多下游，结果按序归并</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class DagEngine {

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);
    private final WorkflowSchemaValidator validator;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DagEngine(List<NodeExecutor> executorBeans, WorkflowSchemaValidator validator) {
        this.validator = validator;
        for (NodeExecutor e : executorBeans) {
            executors.put(e.type(), e);
        }
        // Start/End 共用同一 executor
        if (executors.containsKey(NodeType.Start)) {
            executors.put(NodeType.End, executors.get(NodeType.Start));
        }
    }

    /**
     * 执行工作流。
     *
     * @param definition 工作流 DAG 定义
     * @param input      初始输入变量
     * @return 执行结束后的上下文快照（含各节点 output_var）
     */
    public WorkflowContext execute(WorkflowDefinition definition, Map<String, Object> input) {
        validator.validate(definition);
        WorkflowContext ctx = new WorkflowContext(input);

        WorkflowNode entry = definition.entry();
        if (entry == null) {
            throw BizException.validation("Workflow has no entry node");
        }

        long start = System.currentTimeMillis();
        Set<String> executed = new HashSet<>();
        executeNode(entry, definition, ctx, executed);
        log.info("Workflow '{}' executed in {}ms", definition.name(), System.currentTimeMillis() - start);
        return ctx;
    }

    /**
     * 递归执行节点（含条件分支与并行多下游）。
     */
    private Object executeNode(WorkflowNode node, WorkflowDefinition def, WorkflowContext ctx, Set<String> executed) {
        if (node == null) {
            return null;
        }
        // 环保护（已在校验阶段拦截，此处兜底）
        if (!executed.add(node.id())) {
            return ctx.get(node.outputVar());
        }

        NodeExecutor executor = executors.get(node.type());
        if (executor == null) {
            throw BizException.internal("No executor for node type: " + node.type());
        }

        Object result = executor.execute(node, ctx);
        executor.storeOutput(node, result, ctx);

        // ① 条件分支：按求值结果跳转
        if (node.type() == NodeType.Condition && result instanceof String target && target != null) {
            executeNode(def.node(target), def, ctx, executed);
            return result;
        }

        // ② 线性 / 多下游（并行）
        List<String> nextIds = node.nextIds();
        if (nextIds.isEmpty()) {
            return result;
        }
        if (nextIds.size() == 1) {
            return executeNode(def.node(nextIds.get(0)), def, ctx, executed);
        }
        // 并行执行多下游
        executeParallel(nextIds, def, ctx, executed);
        return result;
    }

    /**
     * 并行执行多下游（虚拟线程）。
     */
    private void executeParallel(List<String> nextIds, WorkflowDefinition def, WorkflowContext ctx, Set<String> executed) {
        List<CompletableFuture<Void>> futures = nextIds.stream()
                .map(id -> CompletableFuture.runAsync(() -> {
                    // 每个并行分支用独立的 executed 副本，避免不同分支间误判环
                    Set<String> branchExecuted = new HashSet<>(executed);
                    executeNode(def.node(id), def, ctx, branchExecuted);
                }, executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}