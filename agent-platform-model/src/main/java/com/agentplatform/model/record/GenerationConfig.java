package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 模型生成参数（常规参数）。
 * <p>对应接口 Schema 的 {@code generation_config}，全部字段可选，运行时按优先级合并。</p>
 *
 * @param model            模型名
 * @param provider         模型服务商
 * @param temperature      温度 0~2
 * @param topP             top-p 采样 0~1
 * @param topK             top-k 采样
 * @param maxTokens        最大 Token 数
 * @param frequencyPenalty 频率惩罚
 * @param presencePenalty  存在惩罚
 * @param stopSequences    停止序列
 * @param responseFormat   输出格式：text / json / json_schema
 * @param timeoutMs        超时（毫秒）
 * @param retry            重试策略
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationConfig(
        String model,
        String provider,
        Double temperature,
        Double topP,
        Integer topK,
        Integer maxTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        List<String> stopSequences,
        String responseFormat,
        Integer timeoutMs,
        Retry retry
) {
    /** 重试策略。 */
    public record Retry(Integer maxAttempts, Long backoffMs) {
    }

    public static GenerationConfig defaults() {
        return new GenerationConfig(
                null, null, 0.7, null, null, 2048,
                null, null, null, "text", 60_000,
                new Retry(2, 1000L));
    }

    /** 便捷合并：将请求级覆盖合并到当前配置（非空覆盖）。 */
    public GenerationConfig merge(GenerationConfig override) {
        if (override == null) {
            return this;
        }
        return new GenerationConfig(
                override.model != null ? override.model : model,
                override.provider != null ? override.provider : provider,
                override.temperature != null ? override.temperature : temperature,
                override.topP != null ? override.topP : topP,
                override.topK != null ? override.topK : topK,
                override.maxTokens != null ? override.maxTokens : maxTokens,
                override.frequencyPenalty != null ? override.frequencyPenalty : frequencyPenalty,
                override.presencePenalty != null ? override.presencePenalty : presencePenalty,
                override.stopSequences != null ? override.stopSequences : stopSequences,
                override.responseFormat != null ? override.responseFormat : responseFormat,
                override.timeoutMs != null ? override.timeoutMs : timeoutMs,
                override.retry != null ? override.retry : retry
        );
    }
}