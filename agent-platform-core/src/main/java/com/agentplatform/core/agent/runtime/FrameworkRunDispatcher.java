package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.model.secret.ModelCredentialsMapper;
import com.agentplatform.core.tool.executor.ToolCallRecord;
import com.agentplatform.framework.api.InputPart;
import com.agentplatform.framework.api.Principal;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.ToolOptions;
import com.agentplatform.model.entity.AgentDefinition;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * 平台 DTO ↔ 框架契约的映射（主链路分流的第一步）。
 *
 * <p>刻意做成**纯映射**：不查库、不拼提示词、不落库。这样：
 * <ul>
 *   <li>可以独立单测，不用起 Spring；</li>
 *   <li>{@code AgentRuntimeService} 将来只需把已有变量传进来，不用重写组装逻辑；</li>
 *   <li>两套运行时（legacy / framework）共用同一份 DTO 出口，前端无感。</li>
 * </ul>
 */
public final class FrameworkRunDispatcher {

    private FrameworkRunDispatcher() {
    }

    /**
     * 组装框架命令。
     *
     * @param systemPrompt 平台已按人格 / 模板 / Skill / RAG 组装好的提示词（框架不重算）
     * @param attachments  已解析成 {@code InputPart} 的附件（没有则为空列表）
     */
    public static RunCommand toCommand(String agentId,
                                       String userText,
                                       String sessionId,
                                       String tenantId,
                                       Principal principal,
                                       AgentDefinition agent,
                                       ModelBindingService.ResolvedModel resolved,
                                       String systemPrompt,
                                       List<InputPart> attachments,
                                       ToolOptions tools,
                                       Map<String, Object> extra) {
        RunCommand.Builder builder = RunCommand.builder(agentId)
                .text(userText)
                .session(sessionId)
                .tenant(tenantId)
                .principal(principal)
                .systemPrompt(systemPrompt)
                .credentials(ModelCredentialsMapper.of(resolved))
                .tools(tools == null ? ToolOptions.none() : tools)
                .parts(attachments == null ? List.of() : attachments)
                .extra(extra == null ? Map.of() : extra);

        if (resolved != null && resolved.model() != null && !resolved.model().isBlank()) {
            builder.model(resolved.model());
        }
        return builder.build();
    }

    /**
     * 把框架结果映射成平台响应。
     *
     * <p><b>工具记录是部分映射</b>：框架的 {@code ToolCall} 只带
     * 「名字 / 成败 / 耗时 / 错误码」——参数与结果文本刻意不放进契约（避免把大结果
     * 和敏感数据搬进响应）。所以这里的 {@code arguments} 为 {@code "{}"}、
     * {@code output} 为空。要让前端看到完整调用，需要框架在 {@code RunResult} 里
     * 增加可选摘要字段（见 R12 的同类问题）。</p>
     */
    public static AgentRunResponse toResponse(RunResult result, String mode, String traceId) {
        if (result == null) {
            throw new IllegalArgumentException("RunResult 不能为空");
        }
        AgentRunResponse.Usage usage = new AgentRunResponse.Usage(
                result.usage().inputTokens(), result.usage().outputTokens(), 0.0);

        // R12：插件产物（audio_url 等）回到平台响应里，否则框架路径下 TTS 会静默失效
        Object audio = result.artifacts().get("audio_url");
        String audioUrl = (audio == null || String.valueOf(audio).isBlank()) ? null : String.valueOf(audio);

        AgentRunResponse response = new AgentRunResponse(
                result.runId(),
                result.sessionId(),
                mode,
                new AgentRunResponse.Output("assistant", result.reply(), audioUrl, result.reasoning()),
                traceId,
                usage,
                List.of(),
                List.of(),
                null);

        if (!result.toolCalls().isEmpty()) {
            List<ToolCallRecord> records = new ArrayList<>();
            for (RunResult.ToolCall call : result.toolCalls()) {
                // R14：入参与结果摘要随结果带出，前端才能展示"查了什么、拿到什么"
                records.add(new ToolCallRecord(call.toolName(),
                        call.arguments() == null ? "{}" : call.arguments(),
                        call.ok(), call.output(), call.errorCode(), call.elapsedMs()));
            }
            return response.withToolCalls(records);
        }
        return response;
    }
}
