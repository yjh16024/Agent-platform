package com.agentplatform.core.agent.dto;

import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.EffectiveConfig;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBindingView;
import com.agentplatform.model.record.Persona;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体详情响应（含完整有效配置与校验结果）。
 */
public record AgentResponse(
        String agentId,
        String tenantId,
        String name,
        String avatar,
        String description,
        Persona persona,
        String systemPrompt,
        GenerationConfig generationConfig,
        Capabilities capabilities,
        ModelBindingView modelBinding,
        String status,
        String visibility,
        String currentVersion,
        List<EffectiveConfig.ResolvedPlugin> effectivePlugins,
        ValidationResult validation,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 保存前校验结果。 */
    public record ValidationResult(boolean ok, List<String> warnings) {
        public static ValidationResult pass() {
            return new ValidationResult(true, List.of());
        }
    }
}