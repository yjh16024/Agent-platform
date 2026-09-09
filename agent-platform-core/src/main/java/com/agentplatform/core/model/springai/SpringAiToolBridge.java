package com.agentplatform.core.model.springai;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.common.util.TraceContext;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.ToolSchemas;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 工具桥接：把平台 {@link ToolRegistry} 中的工具暴露为 Spring AI {@link ToolCallback}，
 * 并负责执行模型返回的 tool_calls，组装原生 {@link ToolResponseMessage}。
 * <p>
 * 与旧实现（把工具结果拼成文本回灌）相比，这里走<b>原生 tool-role 消息</b>：
 * 模型看到的是标准 assistant(tool_calls) → tool(result) 序列，语义更准确。
 * 轮次上限仍由 {@link SpringAiModelAdapter} 控制（{@code MAX_TOOL_ROUNDS}），避免模型死循环。
 * </p>
 * <p>租户 / 运行上下文从 {@link TraceContext}（ScopedValue）读取，与运行入口保持一致。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.springai.enabled", havingValue = "true", matchIfMissing = false)
public class SpringAiToolBridge {

    private final ToolRegistry registry;
    private final ToolExecutor executor;

    public SpringAiToolBridge(@Autowired(required = false) ToolRegistry registry,
                              @Autowired(required = false) ToolExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    /**
     * 按请求白名单构造工具声明（只声明不执行，执行在 {@link #execute}）。
     */
    public List<ToolCallback> callbacks(List<ModelAdapter.ToolSpec> specs) {
        List<ToolCallback> out = new ArrayList<>();
        if (specs == null || specs.isEmpty() || registry == null) {
            return out;
        }
        for (ModelAdapter.ToolSpec spec : specs) {
            if (spec == null || spec.name() == null) {
                continue;
            }
            Tool tool = registry.get(spec.name());
            if (tool == null) {
                continue;
            }
            String description = spec.description() != null ? spec.description() : tool.description();
            // 规范化：厂商要求 JSON Schema 必须是 type:object，null/{} 会触发 400
            String schema = ToolSchemas.orEmpty(spec.inputSchema()).toString();
            out.add(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                            .name(spec.name())
                            .description(description == null ? spec.name() : description)
                            .inputSchema(schema)
                            .build();
                }

                @Override
                public String call(String toolInput) {
                    return invoke(spec.name(), toolInput);
                }
            });
        }
        return out;
    }

    /**
     * 执行本轮模型请求的工具，并组装成原生工具响应消息。
     */
    public ToolResponseMessage execute(List<AssistantMessage.ToolCall> calls) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (calls == null) {
            return ToolResponseMessage.builder().responses(responses).build();
        }
        for (AssistantMessage.ToolCall call : calls) {
            if (call == null || call.name() == null) {
                continue;
            }
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), invoke(call.name(), call.arguments())));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private String invoke(String toolName, String argumentsJson) {
        if (executor == null) {
            return "(工具执行器未配置)";
        }
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? JsonUtils.mapper().createObjectNode()
                    : JsonUtils.mapper().readTree(argumentsJson);
            ToolResult tr = executor.run(toolName, args,
                    ToolContext.of(TraceContext.tenantId(), "", TraceContext.runId()));
            if (tr.output() != null) {
                return tr.output().isTextual() ? tr.output().asText() : tr.output().toString();
            }
            return tr.error() == null ? "" : tr.error();
        } catch (Exception e) {
            log.warn("Spring AI tool invoke failed: {} - {}", toolName, e.getMessage());
            return "(工具执行失败：" + e.getMessage() + ")";
        }
    }
}
