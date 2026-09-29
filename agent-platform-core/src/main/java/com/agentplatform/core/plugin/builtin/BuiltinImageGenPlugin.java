package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.plugin.sdk.ConfigFieldDef;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置插件：<b>图片生成</b>。
 *
 * <h3>★ 为什么用「工具 + 返回 URL」而不是「{@code after_llm} 附加产物」</h3>
 * 平台目前的附加产物通道只有前端渲染的 {@code audioUrl}（TTS）——
 * {@code after_llm} 返回别的 key（比如 {@code image_url}）<b>前端不会渲染</b>，
 * 用户什么都看不到。
 *
 * <p>而「工具返回一个 URL」这条路是通的：工具结果会回灌给模型，模型把它写进回复正文，
 * 前端按 Markdown 渲染成可点的链接。<b>用户点开就能看图。</b>
 * 这是在不改前端的前提下让图片"可见"的唯一做法（对标 dshmarket 的 {@code dsh-image-gen}，
 * 它也是把图片放回对话里）。</p>
 *
 * <h3>协议：OpenAI 兼容 {@code /v1/images/generations}</h3>
 * 与 TTS 插件同一思路 —— 请求体 {@code {model, prompt, n, size, response_format}}、
 * 响应 {@code {data:[{url}]}} 是事实标准，硅基流动 / OpenAI / 大量中转网关都照它实现。
 * 因此换服务商只改 {@code baseUrl} / {@code model} 两项配置。</p>
 *
 * <h3>⚠️ 只支持返回 URL 的服务商</h3>
 * 有些服务商（或某些模型）会返回 {@code b64_json} 内联图片而不是 URL。
 * 那种情况下本插件<b>明确告知不支持</b>，而不是默默失败 ——
 * 因为内联图片要落盘 + 建下载链接，涉及平台的文件服务，属于另一件事（见类末说明）。
 */
@Slf4j
@Component
public class BuiltinImageGenPlugin implements ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_image_gen";

    public static final String TOOL_NAME = "generate_image";

    // ---------------- 配置键（与前端表单字段一致） ----------------

    public static final String CFG_API_KEY = "apiKey";
    public static final String CFG_BASE_URL = "baseUrl";
    public static final String CFG_MODEL = "model";
    public static final String CFG_SIZE = "size";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_MODEL = "Kwai-Kolors/Kolors";
    private static final String DEFAULT_SIZE = "1024x1024";

    /**
     * 生成比搜索慢得多（厂商侧要跑扩散模型），所以超时给得比搜索宽。
     * 30 秒是"能等到结果"与"别让用户以为卡死"之间的折中。
     */
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final int HARD_MAX_TIMEOUT_MS = 180_000;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 按 agentId 隔离配置（插件是单例；理由同 BuiltinTtsPlugin）。 */
    private final Map<String, ImageConfig> configByAgent = new ConcurrentHashMap<>();
    private final Map<Integer, OkHttpClient> clientsByTimeout = new ConcurrentHashMap<>();

    // ---------------- Plugin ----------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String name() {
        return "图片生成";
    }

    @Override
    public String description() {
        return "给智能体一个 generate_image 工具（OpenAI 兼容 /v1/images/generations，"
                + "默认对接硅基流动 Kolors）。生成的图片以链接形式出现在回复里，点开即可查看。"
                + "需在挂载配置里填写 API Key，并在对话页打开「工具」开关。";
                }

                /**
                * 配置项声明（键名与 {@link ImageConfig#of} 读取的完全一致）。
                *
                * <p>API Key 标为必填：与联网搜索不同，这个插件<b>没有免密钥的兜底路径</b> ——
                * 不填就一定画不出图，所以要在填的时候就让人知道。</p>
                */
                @Override
                public List<ConfigFieldDef> configFields() {
                return List.of(
                ConfigFieldDef.secret(CFG_API_KEY, "API Key")
                        .withRequired()
                        .ph("sk-...")
                        .hint("必填。与「文本转语音」用的是同一家（硅基流动）的 Key，可以填同一个"),
                ConfigFieldDef.text(CFG_BASE_URL, "服务地址").def(DEFAULT_BASE_URL),
                ConfigFieldDef.text(CFG_MODEL, "模型")
                      .def(DEFAULT_MODEL)
                      .hint("默认 Kolors；换模型前请确认该模型在服务商处可用于图片生成"),
                ConfigFieldDef.text(CFG_SIZE, "图片尺寸")
                      .def(DEFAULT_SIZE)
                      .hint("形如 1024x1024。尺寸越大越慢、也越贵"),
                ConfigFieldDef.number(CFG_TIMEOUT_MS, "超时（毫秒）", 1000, HARD_MAX_TIMEOUT_MS)
                      .def(String.valueOf(DEFAULT_TIMEOUT_MS))
                      .hint("出图较慢，默认 60 秒"));
                }

                @Override
                public void onAttach(PluginContext ctx) {
        ImageConfig cfg = ImageConfig.of(ctx.config());
        configByAgent.put(ctx.agentId(), cfg);
        log.info("[image-gen] 已挂载到 agent={} baseUrl={} model={} 已配置密钥={}",
                ctx.agentId(), cfg.baseUrl(), cfg.model(), cfg.hasApiKey());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        configByAgent.remove(ctx.agentId());
    }

    // ---------------- 工具 ----------------

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "图片内容描述。建议写清主体、风格、构图与氛围，越具体越好");
        ObjectNode size = props.putObject("size");
        size.put("type", "string");
        size.put("description", "图片尺寸，如 1024x1024（方图）、1024x768（横图）、768x1024（竖图）");
        ArrayNode required = schema.putArray("required");
        required.add("prompt");

        return List.of(new PluginTool(TOOL_NAME,
                "根据文字描述生成一张图片，返回图片链接。用户要求「画一张」「生成图片」时使用。"
                        + "链接会出现在你的回复里，请原样给出以便用户点开查看。",
                schema,
                this::handleGenerate));
    }

    /**
     * 工具入口。
     *
     * <p>校验失败<b>直接抛异常</b>（宿主转成 {@code ToolResult.fail} 回给模型重试）。</p>
     */
    private JsonNode handleGenerate(JsonNode args, PluginContext ctx) {
        String prompt = args == null ? null : args.path("prompt").asText(null);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("参数 prompt 不能为空");
        }
        ImageConfig cfg = configByAgent.getOrDefault(ctx == null ? "" : ctx.agentId(), ImageConfig.defaults());

        if (!cfg.hasApiKey()) {
            // 明确说清"缺什么、去哪配"，而不是让模型以为是自己调用错了
            throw new IllegalStateException("图片生成插件尚未配置 API Key。"
                    + "请提示用户到「插件 → 该插件 → 挂载配置」填入密钥后再试。");
        }

        String size = args.path("size").asText(null);
        if (size == null || size.isBlank()) {
            size = cfg.size();
        }

        try {
            return JsonNodeFactory.instance.textNode(generate(prompt.trim(), size, cfg));
        } catch (Exception e) {
            log.warn("[image-gen] 生成失败 agent={} reason={}",
                    ctx == null ? null : ctx.agentId(), e.getMessage());
            throw new IllegalStateException("图片生成失败：" + e.getMessage()
                    + "。可以换一种描述重试，或告知用户当前无法生成。");
        }
    }

    private String generate(String prompt, String size, ImageConfig cfg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        // 要 URL 而不是 base64：base64 会把几百 KB 灌进上下文，模型用不上还占满窗口
        body.put("response_format", "url");

        Request request = new Request.Builder()
                .url(cfg.imagesUrl())
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform/1.0")
                .build();

        JsonNode root;
        try (Response response = clientFor(cfg.timeoutMs()).newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("服务商返回 HTTP " + response.code() + " " + snippet(raw));
            }
            if (raw.isBlank()) {
                throw new IllegalStateException("服务商返回空响应");
            }
            root = JsonUtils.toJsonNode(raw);
        }

        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("响应里没有 data 数组（可能是服务商格式不同）");
        }
        JsonNode first = data.get(0);
        String url = first.path("url").asText(null);
        if (url != null && !url.isBlank()) {
            return "已生成图片：\n" + url + "\n\n（请把上面这个链接原样放在回答里，用户点击即可查看）";
        }

        // b64_json：能识别但明确不支持 —— 比"什么都没返回"有用得多
        String b64 = first.path("b64_json").asText(null);
        if (b64 != null && !b64.isBlank()) {
            throw new IllegalStateException("该服务商返回的是内联图片（b64_json）而不是链接，当前版本不支持。"
                    + "请在插件配置里改用返回 URL 的模型/服务商（如硅基流动的 Kolors）");
        }
        throw new IllegalStateException("响应里既没有 url 也没有 b64_json");
    }

    private OkHttpClient clientFor(int timeoutMs) {
        return clientsByTimeout.computeIfAbsent(timeoutMs, ms -> new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.min(ms, 10_000)))
                .readTimeout(Duration.ofMillis(ms))
                .writeTimeout(Duration.ofMillis(ms))
                .build());
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    // ---------------- 配置 ----------------

    /**
     * 一次挂载的图片生成配置快照（不可变）。
     *
     * @param apiKey    服务密钥；为空时工具会明确提示"未配置"而不是静默失败
     * @param baseUrl   服务地址（OpenAI 兼容）
     * @param model     图像模型
     * @param size      默认尺寸（模型可在单次调用里覆盖）
     * @param timeoutMs 单次生成超时
     */
    public record ImageConfig(String apiKey, String baseUrl, String model, String size, int timeoutMs) {

        static ImageConfig defaults() {
            return new ImageConfig(null, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_SIZE, DEFAULT_TIMEOUT_MS);
        }

        public static ImageConfig of(JsonNode config) {
            if (config == null || config.isNull()) {
                return defaults();
            }
            return new ImageConfig(
                    blankToNull(str(config, CFG_API_KEY)),
                    blankToDefault(str(config, CFG_BASE_URL), DEFAULT_BASE_URL),
                    blankToDefault(str(config, CFG_MODEL), DEFAULT_MODEL),
                    blankToDefault(str(config, CFG_SIZE), DEFAULT_SIZE),
                    (int) clamp(num(config, CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 1000, HARD_MAX_TIMEOUT_MS));
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * 拼出图片生成端点。
         *
         * <p>容错用户的填法：裸域名 / 带 {@code /v1} / 带尾斜杠 / 直接填完整端点，都能正确解析
         * （与 TTS 插件的 {@code speechUrl()} 同一套规则）。</p>
         */
        public String imagesUrl() {
            String base = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
            if (base.endsWith("/images/generations")) {
                return base;
            }
            if (!base.endsWith("/v1")) {
                base = base + "/v1";
            }
            return base + "/images/generations";
        }

        private static String str(JsonNode node, String key) {
            JsonNode v = node.path(key);
            return v.isMissingNode() || v.isNull() ? null : v.asText();
        }

        private static double num(JsonNode node, String key, double fallback) {
            JsonNode v = node.path(key);
            if (v.isMissingNode() || v.isNull()) {
                return fallback;
            }
            double d = v.asDouble(fallback);
            return Double.isFinite(d) ? d : fallback;
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }

        private static String blankToDefault(String v, String fallback) {
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
