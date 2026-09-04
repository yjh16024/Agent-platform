package com.agentplatform.core.model.factory;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.adapter.AnthropicAdapter;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.adapter.OpenAiCompatibleAdapter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型工厂（工厂模式）。
 * <p>
 * 按 provider 名称创建/缓存模型适配器。所有云厂商模型统一通过 LiteLLM Proxy
 * （OpenAI 兼容协议）接入；本地模型使用 {@link MockModelAdapter} 兜底，保证
 * 无外部依赖时平台仍可运行（用于开发/测试/演示）。
 * </p>
 */
@Slf4j
@Component
public class ModelProviderFactory {

    private final Map<String, ModelAdapter> adapters = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient;

    public ModelProviderFactory(
            @Value("${spring.ai.openai.base-url:http://localhost:4000}") String baseUrl,
            @Value("${spring.ai.openai.api-key:sk-local}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 支持的服务商列表（统一走 LiteLLM 兼容协议）。
     */
    public static final String[] SUPPORTED_PROVIDERS = {
            "auto", "openai", "anthropic", "qwen", "ernie", "hunyuan", "deepseek", "local"
    };

    /**
     * 按名称获取适配器（懒加载 + 缓存）。
     */
    public ModelAdapter get(String provider) {
        String key = normalize(provider);
        return adapters.computeIfAbsent(key, this::create);
    }

    private String normalize(String provider) {
        String p = provider == null || provider.isBlank() ? "auto" : provider.toLowerCase();
        return switch (p) {
            case "auto", "openai", "qwen", "ernie", "hunyuan", "deepseek" -> "openai";
            case "anthropic" -> "anthropic";   // Anthropic 走 Messages API 协议（x-api-key + /v1/messages）
            case "local", "mock" -> "local";
            default -> p;
        };
    }

    private ModelAdapter create(String key) {
        if ("local".equals(key)) {
            return new MockModelAdapter();
        }
        if ("anthropic".equals(key)) {
            return new AnthropicAdapter(key, baseUrl, apiKey, httpClient);
        }
        return new OpenAiCompatibleAdapter(key, baseUrl, apiKey, httpClient);
    }

    /**
     * 判断 provider 是否受支持（忽略大小写）。
     */
    public boolean supports(String provider) {
        if (provider == null || provider.isBlank()) {
            return true;
        }
        for (String p : SUPPORTED_PROVIDERS) {
            if (p.equalsIgnoreCase(provider)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 未知 provider 异常。
     */
    public BizException unsupported(String provider) {
        return new BizException("UNSUPPORTED_PROVIDER", "unsupported provider: " + provider);
    }
}