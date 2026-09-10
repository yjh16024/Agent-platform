package com.agentplatform.core.model.springai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI 版模型工厂（spring-ai 2.0.x）。
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
 * <p>
 * <b>Spring AI 2.0 迁移说明</b>：2.0 的 openai / anthropic 模块改为基于<b>官方厂商 SDK</b>
 * （{@code com.openai:openai-java-core} / {@code com.anthropic:anthropic-java-core}），
 * 原先的 {@code OpenAiApi} / {@code AnthropicApi} 类已移除，改由
 * {@link OpenAiSetup#setupSyncClient} / {@link AnthropicSetup#setupSyncClient} 构建官方客户端；
 * 重试与超时改由 SDK 的 {@code maxRetries} / {@code timeout} 承担（替代原自研 RestClient 拦截器）。
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

    /** 瞬态错误重试次数（429 / 5xx），由官方 SDK 内置重试执行，语义与原自研适配器一致。 */
    static final int MAX_RETRIES = 2;

    /** 请求超时（对齐原自研适配器的 read timeout 120s）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

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
        log.info("Spring AI model factory enabled (spring-ai 2.0.x, observationRegistry={}, ua={})",
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
        // 自定义 UA：官方 SDK 默认 UA 会被 setupSyncClient 内的 putAllHeaders 覆盖为自定义值
        Map<String, String> headers = Map.of("User-Agent", userAgent);

        if (ANTHROPIC_FAMILY.equals(family)) {
            String url = normalizeBaseUrl(baseUrl, DEFAULT_ANTHROPIC_BASE);
            AnthropicClient client = AnthropicSetup.setupSyncClient(
                    url,
                    orEmpty(apiKey),
                    TIMEOUT,
                    MAX_RETRIES,
                    null,          // proxy
                    headers,
                    observationRegistry,
                    null);         // meterRegistry
            // Spring AI 2.0 的 ChatModel 需要同时提供同步与异步客户端，
            // 只给同步会由 Builder 用默认参数自建异步客户端（无凭证 → IllegalStateException）。
            AnthropicClientAsync clientAsync = AnthropicSetup.setupAsyncClient(
                    url,
                    orEmpty(apiKey),
                    TIMEOUT,
                    MAX_RETRIES,
                    null,
                    headers);
            return AnthropicChatModel.builder()
                    .anthropicClient(client)
                    .anthropicClientAsync(clientAsync)
                    .observationRegistry(observationRegistry)
                    .build();
        }

        String url = normalizeBaseUrl(baseUrl, DEFAULT_OPENAI_BASE);
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                url,
                orEmpty(apiKey),
                null,               // credential
                null,               // azureDeploymentName
                null,               // azureOpenAiServiceVersion
                null,               // organizationId
                false,              // isAzure
                false,              // isGitHubModels
                null,               // modelName
                TIMEOUT,
                MAX_RETRIES,
                null,               // proxy
                headers,
                observationRegistry,
                null,               // meterRegistry
                List.of());         // httpClientCustomizers
        // 同上：异步客户端必须显式提供（参数与同步版本一致）。
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                url,
                orEmpty(apiKey),
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                TIMEOUT,
                MAX_RETRIES,
                null,
                headers,
                observationRegistry,
                null,
                List.of());
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 端点归一化：Spring AI 2.0 走官方 SDK，baseUrl 必须包含版本路径
     * （如 {@code https://api.deepseek.com/v1}）；缺失时补 {@code /v1}，与原 1.x 行为对齐。
     */
    private static String normalizeBaseUrl(String baseUrl, String fallback) {
        String base = blank(baseUrl) ? fallback : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (!base.matches(".*/v\\d+$")) {
            base = base + "/v1";
        }
        return base;
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
