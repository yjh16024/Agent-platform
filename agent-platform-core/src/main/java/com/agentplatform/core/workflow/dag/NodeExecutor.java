package com.agentplatform.core.workflow.dag;

import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;

/**
 * 节点执行器（策略模式）。
 * <p>每种节点类型一个执行器，Spring 自动收集，DAG 引擎按类型分发。</p>
 */
public interface NodeExecutor {

    /**
     * 支持的节点类型。
     */
    NodeType type();

    /**
     * 执行节点，返回结果（写入下游上下文）。
     *
     * @param node 节点
     * @param ctx  工作流上下文
     * @return 执行结果对象
     */
    Object execute(WorkflowNode node, WorkflowContext ctx);

    /**
     * 执行后处理：将结果写入 output_var（模板方法，DAG 引擎统一调用）。
     * 结果为 null 时不写入（ConcurrentHashMap 不允许 null 值，且 null 输出无意义）。
     */
    default void storeOutput(WorkflowNode node, Object result, WorkflowContext ctx) {
        if (node.outputVar() != null && !node.outputVar().isBlank() && result != null) {
            ctx.set(node.outputVar(), result);
        }
    }
}