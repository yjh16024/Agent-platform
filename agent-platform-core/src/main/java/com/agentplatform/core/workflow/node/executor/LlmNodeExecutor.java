package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
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

    /**
     * 模型绑定解析（拿到 baseUrl / apiKey）。
     *
     * <p>用字段注入而非构造参数，是为了<b>不改 {@code @RequiredArgsConstructor} 生成的签名</b> ——
     * 构造签名一变，既有单测（直接 new 的）会全部编译失败。这里用 {@code required = false}
     * 保持"没有它也能跑"，只是 baseUrl 为 null（回退全局网关，即修复前的行为）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelBindingService modelBindingService;

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

        /*
         * ★ 解析模型绑定（2026-10-12 修）。
         *
         * 此前这里用 ChatRequest 的旧重载（不带 baseUrl/apiKey），于是 **baseUrl 为 null**，
         * 适配器只能回退到全局的 {@code spring.ai.openai.base-url}（默认 http://localhost:4000，
         * 那是 LiteLLM 网关）。结果：**在「模型设置」页配好的 deepseek 直连完全没被用上**，
         * 工作流一跑就报
         *   OpenAIIoException: Request failed
         *   Caused by: ConnectException: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4000
         * —— 报错还发生在连接层，看不出跟"模型设置"有关，很难往那想。
         *
         * 传 null 是有意的：工作流节点没有 agent 上下文，而 resolve(null) 正好会走
         * 「回退平台默认对话模型（模型设置页）」那条路 —— 那才是用户配过的地方。
         *
         * provider/model 的优先级：**节点显式填的 > 平台绑定**（节点上填了就该听它的）。
         */
        ModelBindingService.ResolvedModel resolved = modelBindingService != null
                ? modelBindingService.resolve(null)
                : null;
        String effProvider = provider;
        String effModel = model;
        String baseUrl = null;
        String apiKey = null;
        if (resolved != null) {
            baseUrl = resolved.baseUrl();
            apiKey = resolved.apiKey();
            if (effProvider == null || effProvider.isBlank() || "auto".equalsIgnoreCase(effProvider)) {
                effProvider = resolved.provider();
            }
            if (effModel == null || effModel.isBlank()) {
                effModel = resolved.model();
            }
        }

        ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                effModel, systemPrompt, rendered, temperature, maxTokens, Map.of(), java.util.List.of(),
                baseUrl, apiKey);
        ModelAdapter.ChatResponse resp = modelRouter.chat(effProvider, req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", resp.content());
        out.put("prompt_tokens", resp.promptTokens());
        out.put("completion_tokens", resp.completionTokens());
        out.put("provider", effProvider);
        out.put("model", effModel == null ? "auto" : effModel);
        log.debug("LLM node {} finished via provider={} model={} baseUrl={}",
                node.id(), effProvider, effModel, baseUrl);
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
