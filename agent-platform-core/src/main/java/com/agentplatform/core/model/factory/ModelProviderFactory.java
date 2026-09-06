package com.agentplatform.core.model.factory;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.adapter.AnthropicAdapter;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.adapter.OpenAiCompatibleAdapter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型工厂（工厂模式）。
 * <p>
 * 按 provider 名称创建/缓存模型适配器。所有云厂商模型统一通过 LiteLLM Proxy
 * （OpenAI 兼容协议）或「直连厂商」接入；本地模型使用 {@link MockModelAdapter} 兜底，保证
 * 无外部依赖时平台仍可运行（用于开发/测试/演示）。
 * </p>
 * <p>请求 User-Agent 可配置（{@code agent-platform.model.user-agent} / {@code MODEL_UA}），
 * 用于规避部分模型网关按 UA 拦截的瞬时风控。</p>
 */
@Slf4j
@Component
public class ModelProviderFactory {

    private static final String DEFAULT_UA = "Mozilla/5.0 (compatible; agent-platform/1.0)";

    private final Map<String, ModelAdapter> adapters = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final String apiKey;
    private final String userAgent;
    private final OkHttpClient httpClient;

    /** 兼容旧测试/调用的便捷构造（走默认 UA）。 */
    public ModelProviderFactory(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_UA);
    }

    /** 主构造（Spring 注入）——多构造时显式标注 @Autowired。 */
    @Autowired
    public ModelProviderFactory(
            @Value("${spring.ai.openai.base-url:http://localhost:4000}") String baseUrl,
            @Value("${spring.ai.openai.api-key:sk-local}") String apiKey,
            @Value("${agent-platform.model.user-agent:Mozilla/5.0 (compatible; agent-platform/1.0)}") String userAgent) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 支持的服务商列表（统一走 LiteLLM 兼容协议 / 直连）。
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
            return new AnthropicAdapter(key, baseUrl, apiKey, userAgent, httpClient);
        }
        return new OpenAiCompatibleAdapter(key, baseUrl, apiKey, userAgent, httpClient);
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
