package com.agentplatform.framework.api;

/**
 * 每请求模型凭证（R13 之一）。
 *
 * <p>框架的 {@code ModelAdapter.ChatRequest} 本来就有 {@code baseUrl} / {@code apiKey} 字段，
 * 但运行时从不从命令里取——导致「智能体级模型绑定」在框架引擎下静默失效。
 * 这个记录把凭证变成显式输入：谁解析出来的（平台是 {@code ModelBindingService}），谁负责填。</p>
 *
 * <p>{@code provider} 只作路由提示：框架运行时自身不做 provider 选择，
 * 它只是把凭证原样透传给适配器。</p>
 *
 * @param provider 服务商标识（可空）
 * @param baseUrl  直连端点（可空：空则用适配器默认）
 * @param apiKey   凭证（可空：空则用适配器默认）
 */
public record ModelCredentials(String provider, String baseUrl, String apiKey) {

    public static ModelCredentials none() {
        return new ModelCredentials(null, null, null);
    }

    public boolean hasEndpoint() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
