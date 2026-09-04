package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Start / End 节点执行器。
 * <p>Start 节点将 input 透传；End 节点为终结节点，直接返回输入。</p>
 */
@Component
public class StartEndNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        // 通过 supports 机制由引擎识别 Start 与 End；此处返回 Start（引擎对 End 单独处理）
        return NodeType.Start;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        if (node.type() == NodeType.End) {
            return ctx.all();
        }
        // Start：input_mapping 作为初始变量写入上下文
        if (node.inputMapping() != null) {
            node.inputMapping().forEach(ctx::set);
        }
        return node.inputMapping() == null ? Map.of() : node.inputMapping();
    }
}