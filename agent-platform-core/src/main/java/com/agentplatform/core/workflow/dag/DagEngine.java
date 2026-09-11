package com.agentplatform.core.workflow.dag;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
 *   <li><b>执行轨迹</b>：{@link #executeWithTrace} 额外产出每个节点的状态 / 耗时 / 输出，
 *       供画布「调试面板」按节点展示（普通执行零开销：不传轨迹容器即不记录）</li>
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

    /** 单个节点的执行轨迹（画布调试面板用）。 */
    public record NodeStep(
            String nodeId,
            String type,
            String name,
            String status,
            long durationMs,
            Object output,
            String error) {
    }

    /** 带轨迹的执行结果。 */
    public record TracedResult(WorkflowContext context, List<NodeStep> steps) {
    }

    /**
     * 执行工作流（无轨迹，行为与历史保持一致）。
     *
     * @param definition 工作流 DAG 定义
     * @param input      初始输入变量
     * @return 执行结束后的上下文快照（含各节点 output_var）
     */
    public WorkflowContext execute(WorkflowDefinition definition, Map<String, Object> input) {
        return executeInternal(definition, input, null);
    }

    /**
     * 执行工作流并返回每个节点的执行轨迹（画布调试用）。
     */
    public TracedResult executeWithTrace(WorkflowDefinition definition, Map<String, Object> input) {
        List<NodeStep> steps = Collections.synchronizedList(new ArrayList<>());
        WorkflowContext ctx = executeInternal(definition, input, steps);
        return new TracedResult(ctx, steps);
    }

    private WorkflowContext executeInternal(WorkflowDefinition definition, Map<String, Object> input,
                                            List<NodeStep> steps) {
        validator.validate(definition);
        WorkflowContext ctx = new WorkflowContext(input);

        WorkflowNode entry = definition.entry();
        if (entry == null) {
            throw BizException.validation("Workflow has no entry node");
        }

        long start = System.currentTimeMillis();
        Set<String> executed = new HashSet<>();
        executeNode(entry, definition, ctx, executed, steps);
        log.info("Workflow '{}' executed in {}ms{}", definition.name(), System.currentTimeMillis() - start,
                steps == null ? "" : (" (" + steps.size() + " steps traced)"));
        return ctx;
    }

    /**
     * 递归执行节点（含条件分支与并行多下游）。
     */
    private Object executeNode(WorkflowNode node, WorkflowDefinition def, WorkflowContext ctx,
                               Set<String> executed, List<NodeStep> steps) {
        if (node == null) {
            return null;
        }
        // 环保护（已在校验阶段拦截，此处兜底）
        if (!executed.add(node.id())) {
            return ctx.get(node.outputVar());
        }

        NodeExecutor executorBean = executors.get(node.type());
        if (executorBean == null) {
            throw BizException.internal("No executor for node type: " + node.type());
        }

        long t0 = System.currentTimeMillis();
        Object result;
        try {
            result = executorBean.execute(node, ctx);
            executorBean.storeOutput(node, result, ctx);
            record(steps, node, "success", System.currentTimeMillis() - t0, result, null);
        } catch (RuntimeException e) {
            record(steps, node, "failed", System.currentTimeMillis() - t0, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }

        // ① 条件分支：按求值结果跳转
        if (node.type() == NodeType.Condition && result instanceof String target && target != null) {
            executeNode(def.node(target), def, ctx, executed, steps);
            return result;
        }

        // ② 线性 / 多下游（并行）
        List<String> nextIds = node.nextIds();
        if (nextIds.isEmpty()) {
            return result;
        }
        if (nextIds.size() == 1) {
            return executeNode(def.node(nextIds.get(0)), def, ctx, executed, steps);
        }
        // 并行执行多下游
        executeParallel(nextIds, def, ctx, executed, steps);
        return result;
    }

    private void record(List<NodeStep> steps, WorkflowNode node, String status, long durationMs,
                        Object output, String error) {
        if (steps == null) {
            return;
        }
        steps.add(new NodeStep(
                node.id(),
                node.type() == null ? null : node.type().name(),
                node.name(),
                status,
                durationMs,
                summarise(output),
                error));
    }

    /** 轨迹里的输出只保留可读摘要，避免把大对象灌进响应。 */
    private Object summarise(Object output) {
        if (output == null) {
            return null;
        }
        if (output instanceof Map<?, ?> map) {
            if (map.size() <= 8) {
                return output;
            }
            return Map.of("_truncated", true, "_size", map.size());
        }
        if (output instanceof String s) {
            return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
        }
        if (output instanceof Number || output instanceof Boolean) {
            return output;
        }
        String text = String.valueOf(output);
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    /**
     * 并行执行多下游（虚拟线程）。
     */
    private void executeParallel(List<String> nextIds, WorkflowDefinition def, WorkflowContext ctx,
                                 Set<String> executed, List<NodeStep> steps) {
        List<CompletableFuture<Void>> futures = nextIds.stream()
                .map(id -> CompletableFuture.runAsync(() -> {
                    // 每个并行分支用独立的 executed 副本，避免不同分支间误判环
                    Set<String> branchExecuted = new HashSet<>(executed);
                    executeNode(def.node(id), def, ctx, branchExecuted, steps);
                }, executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}
