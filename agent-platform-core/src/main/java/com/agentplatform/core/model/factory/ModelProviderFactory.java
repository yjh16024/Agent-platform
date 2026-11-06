package com.agentplatform.core.model.factory;

import com.agentplatform.framework.api.ModelAdapter;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.adapter.AnthropicAdapter;
import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.adapter.OpenAiCompatibleAdapter;
import com.agentplatform.core.model.adapter.RuleEngineModelAdapter;
import com.agentplatform.core.model.springai.SpringAiChatModelFactory;
import com.agentplatform.core.model.springai.SpringAiModelAdapter;
import com.agentplatform.core.model.springai.SpringAiToolBridge;
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
    /** 是否请求「深度思考」（DeepSeek 专有，见 OpenAiCompatibleAdapter.buildChatBody）。 */
    private final boolean thinkingEnabled;

    /**
     * Spring AI 版模型实现（可选）。
     * <p>仅当 {@code agent-platform.springai.enabled=true} 时该 Bean 才存在，届时
     * OpenAI 兼容与 Anthropic 走 Spring AI 的 ChatModel，其余逻辑（凭证解析 / 加密 / 掩码 /
     * 路由）保持不变；未启用时完全走原有自研适配器。</p>
     */
    private SpringAiChatModelFactory springAi;

    /** Spring AI 工具桥接（与 springAi 同时启用）。 */
    private SpringAiToolBridge springAiTools;

    @Autowired(required = false)
    public void setSpringAiChatModelFactory(SpringAiChatModelFactory springAi) {
        this.springAi = springAi;
    }

    @Autowired(required = false)
    public void setSpringAiToolBridge(SpringAiToolBridge springAiTools) {
        this.springAiTools = springAiTools;
    }

    /** 兼容旧测试/调用的便捷构造（走默认 UA，默认开思考）。 */
    public ModelProviderFactory(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_UA, true);
    }

    /** 主构造（Spring 注入）——多构造时显式标注 @Autowired。 */
    @Autowired
    public ModelProviderFactory(
            @Value("${spring.ai.openai.base-url:http://localhost:4000}") String baseUrl,
            @Value("${spring.ai.openai.api-key:sk-local}") String apiKey,
            @Value("${agent-platform.model.user-agent:Mozilla/5.0 (compatible; agent-platform/1.0)}") String userAgent,
            @Value("${agent-platform.model.thinking.enabled:true}") boolean thinkingEnabled) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent;
        this.thinkingEnabled = thinkingEnabled;
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
            "auto", "openai", "anthropic", "qwen", "ernie", "hunyuan", "deepseek",
            /*
             * siliconflow（2026-10-23 补）。
             *
             * 这已经是**第四个**要改的地方了 —— normalize 的 switch、
             * SpringAiChatModelFactory.family、DictSeeder 的字典，都登记过，
             * 唯独漏了这个白名单，于是 ModelRouter 直接回
             * "unsupported provider: siliconflow"，连适配器都没建。
             */
            "siliconflow", "local",
            "rule", "rule-engine"
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
            /*
             * siliconflow（硅基流动）也是 OpenAI 兼容协议（2026-10-23 补）。
             *
             * 之前它只出现在嵌入侧字典里，对话侧没登记 —— 智能体把 provider
             * 设成 siliconflow 会走到 default 分支，建出一个不存在的适配器。
             * 它和 qwen / deepseek 的区别只在 baseUrl。
             */
            case "auto", "openai", "qwen", "ernie", "hunyuan", "deepseek", "siliconflow" -> "openai";
            case "anthropic" -> "anthropic";   // Anthropic 走 Messages API 协议（x-api-key + /v1/messages）
            case "local", "mock" -> "local";
            case "rule", "rule-engine" -> "rule";   // 规则引擎：确定性应答，零成本
            default -> p;
        };
    }

    private ModelAdapter create(String key) {
        if ("local".equals(key)) {
            return new MockModelAdapter();
        }
        if ("rule".equals(key)) {
            return new RuleEngineModelAdapter();
        }
        /*
         * 通道选择 **按能力，而不是按厂商名**（2026-10-19）。
         *
         * <p>Spring AI 的 `ChatResponse` 抽象里只有 text / toolCalls / usage，
         * **没有 reasoning 的位置** —— 厂商的 `reasoning_content` 这类扩展字段进不了它的模型，
         * 也就传不到上层（这不是配置问题，是那层封装不提供）。</p>
         *
         * <p>所以规则很简单：</p>
         * <ul>
         *   <li><b>开启思考</b>（默认）：所有厂商都走自研适配器 —— 它们直接读原始 JSON，
         *       能拿到任何厂商的思考字段（读取侧已做归一化，见各适配器的 extractReasoning）；</li>
         *   <li><b>关闭思考</b>：才走 Spring AI（官方 SDK，标准能力更稳，还能省掉思考的 token）。</li>
         * </ul>
         *
         * <p>这样"换模型"不需要改任何配置：只要 provider 是 OpenAI 兼容或 Anthropic，
         * 在哪条通道都能跑，只是能不能看到思考过程取决于是否开了思考模式。</p>
         */
        if (springAi != null && SpringAiChatModelFactory.isSpringAiFamily(key) && !thinkingEnabled) {
            log.info("Using Spring AI adapter（thinking 已关闭）: {}", key);
            return new SpringAiModelAdapter(key, springAi, baseUrl, apiKey, springAiTools);
        }
        if ("anthropic".equals(key)) {
            return new AnthropicAdapter(key, baseUrl, apiKey, userAgent, httpClient, thinkingEnabled);
        }
        return new OpenAiCompatibleAdapter(key, baseUrl, apiKey, userAgent, httpClient, thinkingEnabled);
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
