package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 并行节点执行器。
 *
 * <h3>它其实很薄，这是有原因的</h3>
 * 「一个节点有多个下游 ⇒ 并行执行」这个能力 {@code DagEngine} <b>本来就有</b>
 * （{@code node.nextIds().size() > 1} 时走 {@code executeParallel}，用虚拟线程并发）。
 * 所以本执行器<b>不需要自己调度</b>，它做两件事：
 *
 * <ol>
 *   <li><b>让并行意图在图上显式可见</b> —— 普通节点恰好有两个下游是"想并行"还是"写错了"，
 *       从图上分不出来；用 {@code Parallel} 类型就明确表达了"这几条就是要一起跑"。
 *       校验器与画布也据此给出正确的提示。</li>
 *   <li><b>产出分支清单</b> —— 返回 {@code branches} 供下游引用（例如汇总节点想知道有几个分支、
 *       分别是谁）。</li>
 * </ol>
 *
 * <h3>★ 各分支的变量作用域是隔离的（2026-09-26 修）</h3>
 * {@code DagEngine.executeParallel} 现在给<b>每个分支一个子 {@link WorkflowContext}</b>：
 * 分支能看到父层已有变量（{@code $&#123;var&#125;} 照常可解析），但各自写入只落在自己的层里，
 * 最后按**分支声明顺序**合并回父层。
 *
 * <p>旧实现是各分支共用同一个 {@code WorkflowContext}，于是两个分支写同名 {@code output_var}
 * 就成了竞态 —— 谁后完成谁赢，且不可复现。现在结果与分支完成先后无关；
 * 若确有同名（后者覆盖前者）会在 {@code DagEngine} 里记一条 WARN 指出是哪个变量。</p>
 *
 * <h3>配置项</h3>
 * 无。分支由节点的 {@code next} 数组声明（至少 2 个才有并行意义；只给 1 个时等价于串行，不报错）。
 */
@Component
public class ParallelNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.Parallel;
    }

    /**
     * 返回分支清单，供下游引用；真正的并发由 {@code DagEngine} 在拿到本节点结果之后发起。
     */
    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        List<String> branches = node.nextIds();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", branches);
        out.put("count", branches.size());
        return out;
    }
}
