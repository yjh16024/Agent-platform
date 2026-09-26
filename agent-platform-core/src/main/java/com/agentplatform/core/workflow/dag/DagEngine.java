package com.agentplatform.core.workflow.dag;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.node.executor.ConditionNodeExecutor;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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
        // 环保护（已在校验阶段拦截，此处兜底）。
        // ⚠️ 必须判 outputVar 是否为 null：ConcurrentHashMap.get(null) 会直接 NPE。
        //    没有 outputVar 的节点（如 End、以及无条件写输出的中间节点）被重复到达时，
        //    这里就是唯一会踩到的地方 —— 修正前它会抛 NullPointerException 而不是安静跳过。
        if (!executed.add(node.id())) {
            return node.outputVar() == null ? null : ctx.get(node.outputVar());
        }

        NodeExecutor executorBean = executors.get(node.type());
        if (executorBean == null) {
            throw BizException.internal("No executor for node type: " + node.type());
        }

        long t0 = System.currentTimeMillis();
        Object result;
        try {
            result = executorBean.execute(node, ctx);
            // Loop 的返回值是「控制指令」而非业务产物，不能写进 output_var（否则变量里会躺着个 LoopControl）
            if (!(result instanceof LoopControl)) {
                executorBean.storeOutput(node, result, ctx);
            }
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

        // ①.5 循环：反复执行循环体（控制指令由 LoopNodeExecutor 解析，这里负责执行）
        //     循环结束后**不做跳转** —— 让下面的 ② 步按本节点的 next 自然继续，
        //     这样"循环体最后一个节点的 next 指向循环后节点"这一约定仍然成立，不必特殊处理。
        if (result instanceof LoopControl loop) {
            runLoop(node, loop, def, ctx, executed, steps);
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

    /**
     * 执行一个 Loop 节点：反复跑循环体，直到满足退出条件 / 到达迭代上限 / 超时。
     *
     * <h3>三个终止条件（缺一不可）</h3>
     * <ol>
     *   <li><b>条件不再成立</b>（配置了 {@code while} 时）—— 正常退出路径；</li>
     *   <li><b>达到 {@code max_iterations}</b> —— 防止写错条件导致死循环；</li>
     *   <li><b>超过 {@code timeout_seconds}</b> —— 防止循环体自身很慢时把请求线程占死。</li>
     * </ol>
     * <p>后两者是**安全阀**：触发时记 WARN 并停止循环，<b>不抛异常</b> ——
     * 这样用户仍能拿到已完成的部分，而不是整条工作流失败。</p>
     *
     * <h3>循环体边界</h3>
     * 从 {@code bodyEntry} 沿 {@code next} 链走，遇到本 Loop 节点的下一个节点（即循环后的落点）或链尽头为止。
     */
    private void runLoop(WorkflowNode loopNode, LoopControl loop, WorkflowDefinition def,
                         WorkflowContext ctx, Set<String> executed, List<NodeStep> steps) {
        List<String> afterIds = loopNode.nextIds();
        String afterLoop = afterIds.isEmpty() ? null : afterIds.get(0);
        long deadline = System.currentTimeMillis() + loop.timeoutMs();
        int rounds = 0;

        for (int i = 0; i < loop.maxIterations(); i++) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Loop '{}' 总超时（{}ms），已执行 {} 轮后停止", loopNode.id(), loop.timeoutMs(), i);
                break;
            }
            if (loop.indexVar() != null) {
                ctx.set(loop.indexVar(), i);
            }

            // while 语义：**先判后跑**。条件在看得到本轮序号之后求值，
            // 不成立则本轮不执行（因此不会出现"多跑一轮"）。
            // 第 0 轮的判断也要做 —— 条件若一开始就不成立，循环体一次都不该跑。
            if (loop.condition() != null && !evaluateLoopCondition(loop.condition(), ctx)) {
                break;
            }

            // ★ 每轮一个全新的环保护集合。
            //   它同时承担两件事：
            //   ① 允许循环体在**不同轮**重复执行（同一个集合复用会让第二轮全被跳过）；
            //   ② 若循环体内部误成环，executeNode 的 executed.add() 会在第二次到达时跳过它，
            //      从而自然终止本轮，不会无限打转。
            Set<String> roundExecuted = new HashSet<>();
            // 预置 Loop 自身：循环体若误指回 Loop，本轮会被跳过而不是重入一次完整循环
            roundExecuted.add(loopNode.id());
            if (afterLoop != null) {
                // 预置"循环后节点"：executeNode 会沿 next 链自动递归，不做这一步的话
                // 循环体末节点会把 afterLoop 也执行掉（每轮一次）。它应当由主流程的 ② 步统一执行。
                roundExecuted.add(afterLoop);
            }

            // 只调用循环体入口一次 —— executeNode 会自己沿 next 链走完整个循环体。
            // ⚠️ 不要在外部再逐节点循环调用：那会和它的内部递归叠加，导致多节点循环体重复执行。
            executeNode(def.node(loop.bodyEntry()), def, ctx, roundExecuted, steps);
            rounds++;
        }
        log.debug("Loop '{}' 结束：执行 {} 轮", loopNode.id(), rounds);
    }

    /**
     * 求值循环继续条件。
     * <p>复用 {@code ConditionNodeExecutor} 的表达式求值（它已支持 {@code ${var} 比较 字面量}、
     * 数值/字符串比较等），避免在引擎里再写一份语义不同的判断。</p>
     */
    private boolean evaluateLoopCondition(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        if (executors.get(NodeType.Condition) instanceof ConditionNodeExecutor conditionExecutor) {
            return conditionExecutor.evaluate(condition, ctx);
        }
        log.warn("未注册 Condition 执行器，循环条件 '{}' 无法求值，按继续处理", condition);
        return true;
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
     *
     * <h3>★ 每个分支有独立的变量作用域（2026-09-26 修）</h3>
     * <p>旧实现里各分支共用同一个 {@code WorkflowContext}，于是两个分支写同名 {@code output_var}
     * 就成了竞态 —— 谁后完成谁赢，且不可复现（同一个工作流多次运行结果可能不同）。</p>
     *
     * <p>现在每个分支拿一个**子作用域**：读时向上查父层（所以 {@code $&#123;var&#125;} 照常能解析到
     * 上游变量），写时只落自己那层。分支跑完后按<b>分支声明顺序</b>合并回父层 ——
     * 因此结果与"谁先跑完"无关，是确定性的。</p>
     *
     * <p>同名冲突仍可能出现（两个分支故意写同一个变量名），此时**后者覆盖前者**并记一条 WARN
     * 指明是哪个变量 —— 不静默吞掉，因为这类覆盖通常是工作流定义写错了。</p>
     */
    private void executeParallel(List<String> nextIds, WorkflowDefinition def, WorkflowContext ctx,
                                 Set<String> executed, List<NodeStep> steps) {
        List<CompletableFuture<Map<String, Object>>> futures = nextIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    // 子作用域：可见父层、写入隔离
                    WorkflowContext branchCtx = new WorkflowContext(ctx);
                    // 每个并行分支用独立的 executed 副本，避免不同分支间误判环
                    Set<String> branchExecuted = new HashSet<>(executed);
                    executeNode(def.node(id), def, branchCtx, branchExecuted, steps);
                    return branchCtx.localAll();
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (int i = 0; i < futures.size(); i++) {
            Map<String, Object> branchVars = futures.get(i).join();
            String branchId = nextIds.get(i);
            branchVars.forEach((key, value) -> {
                if (ctx.localContains(key)) {
                    log.warn("并行分支 '{}' 写入了已被占用的变量 '{}'，本次覆盖先前值；"
                            + "各分支如需各自保留结果，请改用不同的 output_var", branchId, key);
                }
                ctx.set(key, value);
            });
        }
    }
}
