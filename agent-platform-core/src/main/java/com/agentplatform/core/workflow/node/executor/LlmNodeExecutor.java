package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 节点执行器。
 * <p>
 * 配置项（snake_case，兼容 camelCase；均为可选，除 prompt 外）：
 * <ul>
 *   <li>{@code prompt} —— 用户提示词，支持 {@code ${var}}（必填）</li>
 *   <li>{@code system_prompt} —— 系统提示词，支持 {@code ${var}}</li>
 *   <li>{@code provider} —— 服务商，默认 {@code auto}（按能力/健康度自动路由）</li>
 *   <li>{@code model} —— 模型名，缺省由绑定/默认值决定</li>
 *   <li>{@code temperature} / {@code max_tokens} —— 生成参数</li>
 * </ul>
 * 输出：{@code {text, prompt_tokens, completion_tokens, provider, model}}。
 * </p>
 */
@Slf4j
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
        String prompt = NodeConfigs.str(node, "prompt", "user_prompt", "userPrompt");
        if (NodeConfigs.blank(prompt)) {
            throw new IllegalArgumentException("LLM node " + node.id() + " requires config.prompt");
        }
        String rendered = ctx.resolveString(prompt);
        String systemPrompt = NodeConfigs.str(node, "system_prompt", "systemPrompt");
        if (systemPrompt != null) {
            systemPrompt = ctx.resolveString(systemPrompt);
        }
        String provider = NodeConfigs.strOr(node, "auto", "provider");
        String model = NodeConfigs.str(node, "model");
        Double temperature = doubleOrNull(node, "temperature");
        Integer maxTokens = intOrNull(node, "max_tokens", "maxTokens");

        ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                model, systemPrompt, rendered, temperature, maxTokens, Map.of());
        ModelAdapter.ChatResponse resp = modelRouter.chat(provider, req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", resp.content());
        out.put("prompt_tokens", resp.promptTokens());
        out.put("completion_tokens", resp.completionTokens());
        out.put("provider", provider);
        out.put("model", model == null ? "auto" : model);
        log.debug("LLM node {} finished via provider={} model={}", node.id(), provider, model);
        return out;
    }

    private Double doubleOrNull(WorkflowNode node, String... keys) {
        Object v = NodeConfigs.raw(node, keys);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intOrNull(WorkflowNode node, String... keys) {
        Object v = NodeConfigs.raw(node, keys);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
