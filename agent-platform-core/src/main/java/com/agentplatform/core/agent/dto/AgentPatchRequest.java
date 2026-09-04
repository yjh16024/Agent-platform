package com.agentplatform.core.agent.dto;

import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.Persona;
import jakarta.validation.Valid;

/**
 * 局部更新请求（只传差异字段，null 表示不修改）。
 * <p>用于 PATCH /api/v1/agents/{agent_id}。</p>
 */
public record AgentPatchRequest(
        String name,
        String avatar,
        String description,
        Persona persona,
        String systemPrompt,
        @Valid GenerationConfig generationConfig,
        Capabilities capabilities,
        ModelBinding modelBinding,
        String status,
        String visibility
) {
}