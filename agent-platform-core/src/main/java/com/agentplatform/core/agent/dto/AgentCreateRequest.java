package com.agentplatform.core.agent.dto;

import com.agentplatform.model.enums.Visibility;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.Persona;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建智能体请求。
 */
public record AgentCreateRequest(
        @NotBlank(message = "name 不能为空")
        @Size(max = 200)
        String name,

        String avatar,
        String description,

        @NotNull(message = "persona 不能为空")
        Persona persona,

        @NotBlank(message = "system_prompt 不能为空")
        String systemPrompt,

        @Valid
        GenerationConfig generationConfig,

        Capabilities capabilities,

        ModelBinding modelBinding,

        Visibility visibility,

        String tenantId
) {
}