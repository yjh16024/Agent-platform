package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型绑定的对外视图（API 响应用）。
 * <p>
 * 与 {@link ModelBinding} 的唯一区别：<b>绝不回传明文 apiKey</b>，仅回传掩码
 * {@code apiKeyMasked}（如 {@code sk-***abcd}）与布尔 {@code hasApiKey}，避免密钥泄露。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelBindingView(
        String provider,
        String model,
        String baseUrl,
        String apiKeyMasked,
        boolean hasApiKey
) {
}