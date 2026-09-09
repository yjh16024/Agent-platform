package com.agentplatform.core.model.springai;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI 版模型工厂（1.1.x）。
 * <p>
 * 只负责「按凭证构建 ChatModel 实例」，<b>不涉及任何凭证解析</b>：
 * 三级回退（智能体 → 平台默认 → 全局兜底）、AES-GCM 加解密、掩码展示
 * 仍然由 {@code ModelBindingService} / {@code ModelKeyCrypto} 负责，本类只是消费
 * 已经解析好的 {@code baseUrl / apiKey}。
 * </p>
 * <p>
 * 默认<b>不启用</b>：仅当 {@code agent-platform.springai.enabled=true} 时本 Bean 才存在，
 * 未启用时 {@code ModelProviderFactory} 走原有自研适配器，行为完全不变。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.springai.enabled", havingValue = "true", matchIfMissing = false)
public class SpringAiChatModelFactory {

    private static final String OPENAI_FAMILY = "openai";
    private static final String ANTHROPIC_FAMILY = "anthropic";
    private static final String DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com";
    private static final String DEFAULT_OPENAI_BASE = "https://api.openai.com";

    /** 瞬态错误重试次数（429 / 5xx），退避 1s、2s，与原有自研适配器一致。 */
    static final int MAX_RETRIES = 2;

    /** 缓存键含凭证摘要（不落明文 key）。 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    private final ObservationRegistry observationRegistry;
    private final String userAgent;

    public SpringAiChatModelFactory(ObservationRegistry observationRegistry) {
        this(observationRegistry, "Mozilla/5.0 (compatible; agent-platform/1.0)");
    }

    @Autowired
    public SpringAiChatModelFactory(@Autowired(required = false) ObservationRegistry observationRegistry,
                                    @Value("${agent-platform.model.user-agent:Mozilla/5.0 (compatible; agent-platform/1.0)}") String userAgent) {
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "Mozilla/5.0 (compatible; agent-platform/1.0)" : userAgent;
        log.info("Spring AI model factory enabled (spring-ai 1.1.x, observationRegistry={}, ua={})",
                observationRegistry != null, this.userAgent);
    }

    /**
     * 按「模型族 + 端点 + 凭证摘要」取（或建）ChatModel。
     */
    public ChatModel chatModel(String provider, String baseUrl, String apiKey) {
        String family = family(provider);
        String key = family + "|" + orEmpty(baseUrl) + "|" + digest(apiKey);
        return cache.computeIfAbsent(key, k -> build(family, baseUrl, apiKey));
    }

    private ChatModel build(String family, String baseUrl, String apiKey) {
        RestClient.Builder rest = restClientBuilder();
        if (ANTHROPIC_FAMILY.equals(family)) {
            // 注：AnthropicApi.Builder 不支持注入自定义 RestClient，故 Anthropic 通道沿用
            // Spring AI 默认客户端（UA/重试策略暂不覆盖，功能不受影响）。
            AnthropicApi api = AnthropicApi.builder()
                    .baseUrl(blank(baseUrl) ? DEFAULT_ANTHROPIC_BASE : baseUrl)
                    .apiKey(orEmpty(apiKey))
                    .build();
            return AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .observationRegistry(observationRegistry)
                    .build();
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(blank(baseUrl) ? DEFAULT_OPENAI_BASE : baseUrl)
                .apiKey(orEmpty(apiKey))
                .restClientBuilder(rest)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 统一 HTTP 客户端：自定义 UA（应对按 UA 拦截的网关/风控）+ 超时 + 瞬态错误退避重试。
     * <p>与原有 {@code OpenAiCompatibleAdapter} 的行为对齐：connect 10s / read 120s，
     * 仅 429 与 5xx 重试最多 2 次（1s、2s）。</p>
     */
    private RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .requestInterceptor(new TransientRetryInterceptor(MAX_RETRIES));
    }

    /**
     * 瞬态错误（429 / 5xx）退避重试拦截器。
     * <p>错误响应未被读取即关闭，可安全重新发起；2xx 与 4xx（非 429）原样返回。</p>
     */
    static final class TransientRetryInterceptor implements ClientHttpRequestInterceptor {

        private final int maxRetries;

        TransientRetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            long backoffMs = 1000L;
            for (int attempt = 0; ; attempt++) {
                ClientHttpResponse response = execution.execute(request, body);
                int status = response.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxRetries) {
                    return response;
                }
                response.close();
                log.warn("Transient upstream error {}, retry {}/{} after {}ms", status, attempt + 1, maxRetries, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("retry interrupted", e);
                }
                backoffMs *= 2;
            }
        }
    }

    /**
     * 归一化模型族：与 {@code ModelProviderFactory#normalize} 保持一致（只取 openai / anthropic）。
     */
    public static String family(String provider) {
        String p = provider == null || provider.isBlank() ? "auto" : provider.toLowerCase();
        return switch (p) {
            case "auto", "openai", "qwen", "ernie", "hunyuan", "deepseek" -> OPENAI_FAMILY;
            case "anthropic" -> ANTHROPIC_FAMILY;
            default -> p;
        };
    }

    public static boolean isSpringAiFamily(String normalizedKey) {
        return OPENAI_FAMILY.equals(normalizedKey) || ANTHROPIC_FAMILY.equals(normalizedKey);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 凭证摘要（SHA-256 前 16 位 hex），仅用于缓存键，避免明文 key 常驻内存结构。 */
    private static String digest(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, d.length); i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }
}
