package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.model.ModelCapability;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 节点执行器。
 * <p>调用模型推理，prompt 支持 ${var} 占位符填充，结果写入 output_var。</p>
 */
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    private final ModelRouter modelRouter;

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String prompt = node.config() == null ? null : (String) node.config().get("prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("LLM node " + node.id() + " requires config.prompt");
        }
        // 填充 ${var}
        String rendered = ctx.resolveString(prompt);
        String model = node.config().get("model") == null ? null : (String) node.config().get("model");

        ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                model, null, rendered, null, null, Map.of());
        ModelAdapter.ChatResponse resp = modelRouter.chat("auto", req);
        return Map.of("text", resp.content(), "prompt_tokens", resp.promptTokens(), "completion_tokens", resp.completionTokens());
    }
}