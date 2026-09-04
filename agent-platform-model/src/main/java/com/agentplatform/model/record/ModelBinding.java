package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 智能体的模型绑定（provider / model / 直连端点 / 凭证）。
 * <p>
 * 用于让用户在仪表盘创建智能体时选定模型与 API Key，而无需修改项目文件。
 * 作为强类型 {@code record} 序列化为 camelCase（{@code provider/model/baseUrl/apiKey}）。
 * </p>
 * <p>
 * 语义区分：作为 <b>前端请求 DTO 字段</b>时 {@code apiKey} 是明文；作为
 * <b>持久化值</b>时 {@code apiKey} 存 AES-GCM 密文（由 {@code ModelBindingService.seal}
 * 加密、{@code resolve} 解密）。数据库默认为空 → 运行时回退到全局配置（见 TODO §一）。
 * </p>
 *
 * @param provider 服务商：openai / anthropic / qwen / ernie / hunyuan / deepseek / local / auto
 * @param model    模型名（provider 原生名，如 deepseek-chat / gpt-4o-mini）
 * @param baseUrl  直连端点（可选，留空走全局 LiteLLM）
 * @param apiKey   凭证（明文入、密文存）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelBinding(
        String provider,
        String model,
        String baseUrl,
        String apiKey
) {
    /** 空绑定（表示未配置，运行时走全局默认）。 */
    public static ModelBinding empty() {
        return new ModelBinding(null, null, null, null);
    }

    /** 是否携带任何有效配置（派生值，不落库、不被 Jackson 序列化）。 */
    @JsonIgnore
    public boolean isConfigured() {
        return (provider != null && !provider.isBlank())
                || (model != null && !model.isBlank())
                || (baseUrl != null && !baseUrl.isBlank())
                || (apiKey != null && !apiKey.isBlank());
    }
}