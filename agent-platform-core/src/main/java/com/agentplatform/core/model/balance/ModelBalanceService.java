package com.agentplatform.core.model.balance;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.model.config.ModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型开放平台账户额度查询。
 * <p>
 * 用<b>已保存的加密凭证</b>（{@link ModelConfigService} 解密后的明文，只在本层内存中使用）去各厂商
 * 开放平台查询剩余额度，向用户展示「这份 Key 还剩多少钱」。
 * </p>
 * <p>
 * <b>厂商支持情况（以官方文档为准，实测为准）</b>：
 * <ul>
 *   <li>{@code deepseek} —— {@code GET /user/balance}，返回 is_available + balance_infos[]；</li>
 *   <li>{@code siliconflow} —— {@code GET /v1/user/info}，返回 data.balance/totalBalance/chargeBalance；</li>
 *   <li>{@code moonshot/kimi} —— {@code GET /v1/users/me/balance}，返回 data.available_balance 等；</li>
 *   <li>其余（openai / qwen / ernie / hunyuan / gemini / local / mock 等）—— <b>官方未开放余额接口</b>，
 *       明确回 {@code supported=false} 并说明原因，绝不假装查询成功。</li>
 * </ul>
 * </p>
 * <p>
 * <b>稳定性约定</b>：本服务只读、无副作用、无事务；任何异常（网络、401、端点不存在）都被捕获并转成
 * {@code ok=false} 的结构化结果，绝不向上抛异常拖垮接口。
 * </p>
 */
@Slf4j
@Service
public class ModelBalanceService {

    private static final String BINDING_CHAT = "chat";
    private static final String BINDING_EMBEDDING = "embedding";

    /** 响应体摘要上限（错误信息截断，避免超长响应污染 UI）。 */
    private static final int BODY_SNIPPET_LIMIT = 300;

    /**
     * 厂商余额端点定义。
     *
     * @param defaultBaseUrl 未配置 baseUrl 时的官方端点
     * @param path           余额路径（拼在 baseUrl 之后）
     * @param stripV1        true 表示拼接前需去掉 baseUrl 结尾的 {@code /v1}
     *                       （DeepSeek 的聊天端点是 {@code https://api.deepseek.com/v1}，
     *                       而余额端点是 {@code https://api.deepseek.com/user/balance}）
     * @param note           不支持时的说明文案（仅不支持厂商用）
     */
    private record Vendor(String defaultBaseUrl, String path, boolean stripV1, String note) {
    }

    /** 支持的厂商：provider（小写）→ 端点定义。 */
    private static final Map<String, Vendor> VENDORS = Map.of(
            "deepseek", new Vendor("https://api.deepseek.com", "/user/balance", true, null),
            "siliconflow", new Vendor("https://api.siliconflow.cn/v1", "/user/info", false, null),
            "moonshot", new Vendor("https://api.moonshot.cn/v1", "/users/me/balance", false, null),
            "kimi", new Vendor("https://api.moonshot.cn/v1", "/users/me/balance", false, null)
    );

    /**
     * 明确不支持余额查询的厂商 → 说明文案（前端展示，避免用户以为是自己配置错了）。
     */
    private static final Map<String, String> UNSUPPORTED = Map.of(
            "openai", "OpenAI 未开放余额查询接口，只能在官网后台查看用量与额度",
            "qwen", "通义千问（DashScope）未开放余额查询接口，请在阿里云控制台查看",
            "dashscope", "通义千问（DashScope）未开放余额查询接口，请在阿里云控制台查看",
            "ernie", "文心一言（千帆）未开放余额查询接口，请在百度智能云控制台查看",
            "hunyuan", "腾讯混元未开放余额查询接口，请在腾讯云控制台查看",
            "gemini", "Gemini 未开放余额查询接口，请在 Google AI Studio 查看",
            "ollama", "本地部署模型按机器资源计价，不存在平台余额概念",
            "local", "本地 Mock 模型不产生费用，不存在余额概念",
            "mock", "本地 Mock 模型不产生费用，不存在余额概念"
    );

    private final ModelConfigService modelConfig;
    private final OkHttpClient httpClient;

    public ModelBalanceService(ModelConfigService modelConfig) {
        this.modelConfig = modelConfig;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(6))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 查询平台已配置绑定的账户额度（默认对话模型 + 嵌入模型）。
     *
     * @return 每条绑定的查询结果（顺序固定：chat → embedding），永不抛异常
     */
    public List<ModelBalanceView> queryAll() {
        ModelConfigService.ChatBinding chat = modelConfig.getChat();
        ModelConfigService.EmbeddingBinding emb = modelConfig.getEmbedding();

        List<ModelBalanceView> out = new ArrayList<>(2);
        out.add(query(new Binding(BINDING_CHAT, chat.provider(), chat.model(), chat.baseUrl(), chat.apiKey())));
        out.add(query(new Binding(BINDING_EMBEDDING, emb.provider(), emb.model(), emb.baseUrl(), emb.apiKey())));
        return out;
    }

    /** 内部统一绑定载体（对话 / 嵌入两种绑定结构一致）。 */
    private record Binding(String binding, String provider, String model, String baseUrl, String apiKey) {

        boolean configured() {
            return (provider != null && !provider.isBlank())
                    || (model != null && !model.isBlank())
                    || (baseUrl != null && !baseUrl.isBlank())
                    || (apiKey != null && !apiKey.isBlank());
        }

        boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        String providerKey() {
            return provider == null ? "" : provider.trim().toLowerCase();
        }
    }

    /**
     * 查询单条绑定的额度。
     */
    private ModelBalanceView query(Binding b) {
        long now = System.currentTimeMillis();
        String provider = b.provider();

        if (!b.configured()) {
            return view(b, false, false, null, null, null, "该绑定未配置（未填写服务商 / 模型 / API Key）", now);
        }
        if (!b.hasKey()) {
            return view(b, true, false, null, null, null, "未配置 API Key，无法查询额度（请先在模型配置页填写）", now);
        }

        String key = b.providerKey();
        Vendor vendor = VENDORS.get(key);
        if (vendor == null) {
            String note = UNSUPPORTED.getOrDefault(key,
                    "服务商「" + provider + "」未提供余额查询接口，请在其开放平台后台查看额度");
            return view(b, false, false, null, null, null, note, now);
        }

        String baseUrl = resolveBaseUrl(vendor, b.baseUrl(), key);
        String url = joinUrl(baseUrl, vendor.path());
        try {
            JsonNode node = get(url, b.apiKey());
            Parsed parsed = parse(key, node);
            return new ModelBalanceView(b.binding(), provider, b.model(), url, true, true, true,
                    parsed.currency(), parsed.available(), parsed.items(), null, now);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[model-balance] 查询失败 provider={} url={} reason={}", provider, url, msg);
            return new ModelBalanceView(b.binding(), provider, b.model(), url, true, true, false,
                    null, null, null, "查询失败：" + msg, now);
        }
    }

    private ModelBalanceView view(Binding b, boolean supported, boolean ok, String currency,
                                  String available, List<ModelBalanceView.Item> items, String message, long now) {
        return new ModelBalanceView(b.binding(), b.provider(), b.model(), null,
                b.configured(), supported, ok, currency, available, items, message, now);
    }

    /**
     * 决定实际请求端点：优先用户配置的 baseUrl（支持中转/自建网关），否则用厂商官方端点。
     */
    private String resolveBaseUrl(Vendor vendor, String configured, String providerKey) {
        String base = configured == null || configured.isBlank() ? vendor.defaultBaseUrl() : configured.trim();
        base = base.replaceAll("/+$", "");
        if (vendor.stripV1() && base.toLowerCase().endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }

    private String joinUrl(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /** 发起 GET（Bearer 认证），失败抛异常（由调用方转成结构化结果）。 */
    private JsonNode get(String url, String apiKey) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform/1.0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " " + snippet(body));
            }
            if (body.isBlank()) {
                throw new IllegalStateException("响应为空");
            }
            return JsonUtils.toJsonNode(body);
        }
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= BODY_SNIPPET_LIMIT ? s : s.substring(0, BODY_SNIPPET_LIMIT) + "...";
    }

    /** 单次查询的解析结果。 */
    private record Parsed(String currency, String available, List<ModelBalanceView.Item> items) {
    }

    /**
     * 按厂商解析响应。字段名严格对齐官方文档，缺失时降级为「尽力而为」而不抛异常。
     */
    private Parsed parse(String providerKey, JsonNode node) {
        return switch (providerKey) {
            case "deepseek" -> parseDeepSeek(node);
            case "moonshot", "kimi" -> parseMoonshot(node);
            case "siliconflow" -> parseSiliconFlow(node);
            default -> new Parsed(null, text(node, "balance"), List.of());
        };
    }

    /** DeepSeek：{is_available, balance_infos:[{currency,total_balance,granted_balance,topped_up_balance}]} */
    private Parsed parseDeepSeek(JsonNode node) {
        JsonNode infos = node.path("balance_infos");
        if (!infos.isArray() || infos.isEmpty()) {
            throw new IllegalStateException("响应缺少 balance_infos 字段");
        }
        JsonNode first = infos.get(0);
        String currency = text(first, "currency");
        String available = text(first, "total_balance");
        List<ModelBalanceView.Item> items = new ArrayList<>();
        items.add(new ModelBalanceView.Item("充值余额", text(first, "topped_up_balance")));
        items.add(new ModelBalanceView.Item("赠金余额", text(first, "granted_balance")));
        boolean usable = node.path("is_available").asBoolean(true);
        items.add(new ModelBalanceView.Item("账户状态", usable ? "可用" : "余额不足，无法调用"));
        return new Parsed(currency, available, items);
    }

    /** Moonshot/Kimi：{code, data:{available_balance, voucher_balance, cash_balance}, status} */
    private Parsed parseMoonshot(JsonNode node) {
        JsonNode data = node.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("响应缺少 data 字段");
        }
        List<ModelBalanceView.Item> items = new ArrayList<>();
        items.add(new ModelBalanceView.Item("代金券余额", text(data, "voucher_balance")));
        items.add(new ModelBalanceView.Item("现金余额", text(data, "cash_balance")));
        return new Parsed("CNY", text(data, "available_balance"), items);
    }

    /**
     * 硅基流动：{data:{balance, totalBalance, chargeBalance, ...}}。
     * 官方未公开字段文档，采用宽松取值：可用余额优先取 balance，其次 totalBalance。
     */
    private Parsed parseSiliconFlow(JsonNode node) {
        JsonNode data = node.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("响应缺少 data 字段");
        }
        List<ModelBalanceView.Item> items = new ArrayList<>();
        items.add(new ModelBalanceView.Item("总余额", text(data, "totalBalance")));
        items.add(new ModelBalanceView.Item("充值余额", text(data, "chargeBalance")));
        items.add(new ModelBalanceView.Item("账户", firstNonBlank(text(data, "name"), text(data, "email"))));
        String available = firstNonBlank(text(data, "balance"), text(data, "totalBalance"));
        return new Parsed("CNY", available, items);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 供测试/前端展示的受支持厂商清单。 */
    public static Map<String, String> supportedVendors() {
        Map<String, String> m = new LinkedHashMap<>();
        VENDORS.forEach((k, v) -> m.put(k, v.defaultBaseUrl() + v.path()));
        return m;
    }
}
