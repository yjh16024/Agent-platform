package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.LoopControl;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import org.springframework.stereotype.Component;

/**
 * 循环节点执行器。
 *
 * <p>本类<b>只解析并校验配置</b>，真正的循环由 {@link com.agentplatform.core.workflow.dag.DagEngine}
 * 执行（原因见 {@link LoopControl} 的类注释）。</p>
 *
 * <h3>配置项（config，snake_case；同时兼容 camelCase）</h3>
 * <table border="1">
 *   <caption>Loop 节点配置</caption>
 *   <tr><th>键</th><th>必填</th><th>说明</th></tr>
 *   <tr><td>{@code loop_body}</td><td>✅</td><td>循环体入口节点 id</td></tr>
 *   <tr><td>{@code max_iterations}</td><td></td><td>迭代上限，默认 {@value #DEFAULT_MAX_ITERATIONS}，
 *       硬上限 {@value #ABSOLUTE_MAX_ITERATIONS}</td></tr>
 *   <tr><td>{@code timeout_seconds}</td><td></td><td>总超时秒数，默认 {@value #DEFAULT_TIMEOUT_SECONDS}</td></tr>
 *   <tr><td>{@code while}</td><td></td><td>继续条件（形如 ${i} &lt; 5，用 ${...} 引用变量）。
 *       <b>不填则跑满 max_iterations 次</b></td></tr>
 *   <tr><td>{@code index_var}</td><td></td><td>把当前迭代序号（从 0 起）写入该变量，供循环体引用</td></tr>
 * </table>
 *
 * <h3>★ 两道安全阀是刻意的，不要去掉</h3>
 * <b>迭代上限</b>与<b>总超时</b>必须同时存在：只靠条件表达式的话，一个写错的条件
 * （或条件依赖的变量恰好恒为真）就能让工作流永久挂住 —— 而这是在一个 HTTP 请求线程里跑的。
 * 到点后引擎是**停止循环并继续往下走**（记 WARN），不是抛异常，
 * 这样超时不会让整条工作流失败、用户还能拿到已完成的部分。
 */
@Component
public class LoopNodeExecutor implements NodeExecutor {

    /** 默认迭代上限：与工具循环的默认轮数保持一致。 */
    static final int DEFAULT_MAX_ITERATIONS = 20;

    /**
     * 迭代次数硬上限。
     * <p>即使配置里写了更大的值也会被夹到这里 —— 防止"填个 1000000 把服务打满"。
     * 需要更多轮次说明该改用长流程编排（见 roadmap 的 E1），而不是把工作流当批处理跑。</p>
     */
    static final int ABSOLUTE_MAX_ITERATIONS = 1000;

    /** 默认总超时（秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    @Override
    public NodeType type() {
        return NodeType.Loop;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String body = NodeConfigs.str(node, "loop_body", "loopBody", "body");
        if (NodeConfigs.blank(body)) {
            throw new IllegalArgumentException(
                    "Loop node " + node.id() + " requires config.loop_body（循环体入口节点 id）");
        }

        int max = NodeConfigs.intVal(node, DEFAULT_MAX_ITERATIONS,
                "max_iterations", "maxIterations", "max_loop");
        if (max < 1) {
            max = 1;
        }
        max = Math.min(max, ABSOLUTE_MAX_ITERATIONS);

        int timeoutSeconds = NodeConfigs.intVal(node, DEFAULT_TIMEOUT_SECONDS,
                "timeout_seconds", "timeoutSeconds", "timeout");
        if (timeoutSeconds < 1) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        long timeoutMs = timeoutSeconds * 1000L;

        String condition = NodeConfigs.str(node, "while", "condition", "while_condition", "whileCondition");
        String indexVar = NodeConfigs.str(node, "index_var", "indexVar");

        return new LoopControl(body, max, timeoutMs, condition, indexVar);
    }
}
