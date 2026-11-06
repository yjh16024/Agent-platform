package com.agentplatform.core.plugin.builtin;

import com.agentplatform.framework.api.ToolResult;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /** 随机种子（2026-10-28）：固定后构图和人脸更稳定，但换姿势仍会变。 */
    public static final String CFG_SEED = "seed";

    /** 负面提示词（2026-10-28）：对多手多脚、画面杂乱有实际效果。 */
    public static final String CFG_NEGATIVE = "negativePrompt";

    /**
     * 默认负面提示词（2026-10-28）。
     *
     * <p>用户的原始需求是"画出来有三条腿、背景经常乱"。这几类问题里，
     * **肢体错误靠正向提示词压不住，靠负面词才有明显改善**。</p>
     */
    private static final String DEFAULT_NEGATIVE =
            "畸形，多余的肢体，多余的手指，三条腿，手部错误，肢体扭曲，"
                    + "画面杂乱，文字，水印，低质量，模糊";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    /**
     * 接口类型（2026-10-22）。
     *
     * <p>各家文生图的协议并不统一：
     * <ul>
     *   <li><b>openai</b> —— {@code POST /v1/images/generations}，返回 {@code {data:[{url}]}}。
     *       硅基流动 / OpenAI / 大量中转网关都是这个格式；</li>
     *   <li><b>dashscope</b> —— 阿里云百炼，**异步两步**：先提交拿 {@code task_id}，
     *       再轮询 {@code /api/v1/tasks/{id}}。路径是 {@code /api/v1/...}，不是 {@code /v1/...}；</li>
     *   <li><b>pollinations</b> —— 免密钥，GET 一个地址直接返回图片。</li>
     * </ul>
     * 默认 {@code auto} 按地址/模型名/有没有 Key 自动判断，用户基本不用管。</p>
     */
    public static final String CFG_PROVIDER = "provider";

    /** 阿里云百炼国内端点（路径是 /api/v1/...，与 OpenAI 的 /v1/... 不同）。 */
    private static final String DASHSCOPE_BASE = "https://dashscope.aliyuncs.com";

    /** 百炼里性价比最高的文生图模型（快、便宜，适合"随手画一张"）。 */
    private static final String DASHSCOPE_MODEL = "wanx2.1-t2i-turbo";

    private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_MODEL = "Kwai-Kolors/Kolors";

    /**
     * 免费兜底通道（2026-10-22 加）。
     *
     * <p>Pollinations.ai —— **无需注册、无需密钥**，GET 这个地址直接返回图片。
     * 实测 1.7s / 40KB JPEG，国内可直连。</p>
     *
     * <p><b>为什么要这个兜底</b>：原先没配 key 就必然抛错，等于"生图"这个能力默认不可用；
     * 而它恰恰是最需要"随手试试"的能力。现在缺密钥时自动降级到免费通道，
     * 质量不如商业模型，但至少能用。</p>
     */
    private static final String POLLINATIONS_URL = "https://image.pollinations.ai/prompt/";
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
                * <p><b>API Key 在 2026-10-22 起改为可选</b>：留空时自动走 Pollinations 免费通道
                * （免注册、免密钥、直接返回图片地址）。填了硅基流动的 Key 才用 Kolors 这类商业模型。</p>
                */
                @Override
                public List<ConfigFieldDef> configFields() {
                return List.of(
                ConfigFieldDef.select(CFG_PROVIDER, "接口类型",
                                List.of("auto", "openai", "dashscope", "pollinations"))
                        .def("auto")
                        .hint("auto 自动判断：地址含 aliyuncs、或模型名以 wanx/qwen-image 开头 → 阿里云百炼；"
                                + "没填 Key → 免费通道；其余按 OpenAI 兼容格式。一般不用改"),
                ConfigFieldDef.secret(CFG_API_KEY, "API Key（可留空）")
                        .ph("sk-...")
                        .hint("留空则用免费通道（Pollinations，免密钥，质量一般但能用）；"
                                + "填硅基流动的 Key 可换用更好的模型。与「文本转语音」可以是同一个 Key"),
                ConfigFieldDef.text(CFG_BASE_URL, "服务地址").def(DEFAULT_BASE_URL),
                ConfigFieldDef.text(CFG_MODEL, "模型")
                      .def(DEFAULT_MODEL)
                      .hint("默认 Kolors；换模型前请确认该模型在服务商处可用于图片生成"),
                ConfigFieldDef.text(CFG_SIZE, "图片尺寸")
                      .def(DEFAULT_SIZE)
                      .hint("形如 1024x1024。尺寸越大越慢、也越贵"),
                /*
                 * 种子与负面词（2026-10-28）。
                 *
                 * 用户的原始问题是「画出来有三条腿、背景乱、同一个人每次都不一样」。
                 * 前两个靠负面词压，第三个靠固定种子改善（但到不了 100%）。
                 */
                ConfigFieldDef.number(CFG_SEED, "随机种子（0 = 每次不同）", 0, 2147483647)
                      .def("0")
                      .hint("固定一个值能让构图和人脸更稳定（约 60~70%），"
                              + "但换姿势仍会变脸 —— 想彻底一致得用参考图"),
                ConfigFieldDef.text(CFG_NEGATIVE, "负面提示词（排除畸形）")
                      .def(DEFAULT_NEGATIVE)
                      .hint("逗号分隔。对「多手多脚、三条腿、画面杂乱、水印」有实际效果"),
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

        String size = args.path("size").asText(null);
        if (size == null || size.isBlank()) {
            size = cfg.size();
        }

        /*
         * 按协议分流（2026-10-22）。
         *
         * 各家文生图的协议并不统一，之前只有一条 OpenAI 兼容路径：
         *   · 百炼（阿里云）走的是 /api/v1/... 的**异步任务**接口，字段名也完全不同；
         *   · 没配 Key 以前直接抛错，现在降级到免费的 Pollinations。
         */
        String prov = cfg.resolveProvider();
        try {
            String out = switch (prov) {
                case "pollinations" -> pollinationsMessage(prompt.trim(), size);
                case "dashscope" -> generateDashScope(prompt.trim(), size, cfg);
                default -> generate(prompt.trim(), size, cfg);
            };
            log.info("[image-gen] ok agent={} provider={} model={} size={}",
                    ctx == null ? null : ctx.agentId(), prov,
                    "dashscope".equals(prov) ? cfg.effectiveModel(prov) : cfg.model(), size);
            return JsonNodeFactory.instance.textNode(out);
        } catch (Exception e) {
            log.warn("[image-gen] 生成失败 agent={} provider={} reason={}",
                    ctx == null ? null : ctx.agentId(), prov, e.getMessage());
            throw new IllegalStateException("图片生成失败（" + prov + "）：" + e.getMessage()
                    + "。可以换一种描述重试，或告知用户当前无法生成。");
        }
    }

    /** 免费通道：只拼地址，不在这里发请求（访问该地址时才触发生成）。 */
    private String pollinationsMessage(String prompt, String size) {
        String url = pollinations(prompt, size);
        return "免费通道生成的图片地址（可直接下载）：\n" + url
                + "\n\n（质量不如付费模型；到「插件 → 该插件 → 挂载配置」填入 API Key 可换用更好的服务商。）";
    }

    /**
     * 拼出 Pollinations 的图片地址（免密钥通道）。
     *
     * <p>它不返回 JSON，而是**直接返回图片**：这个 URL 本身就是可下载的图片地址，
     * 访问一次即触发生成（同一 URL 会被缓存）。所以这里不用发请求，只拼地址。</p>
     */
    private static String pollinations(String prompt, String size) {
        int[] wh = parseSize(size);
        return POLLINATIONS_URL + URLEncoder.encode(prompt, StandardCharsets.UTF_8)
                + "?width=" + wh[0] + "&height=" + wh[1]
                + "&nologo=true&seed=" + (System.currentTimeMillis() % 1_000_000);
    }

    /** {@code "1024x1024"} / {@code "512*768"} / {@code "1024×1024"} → [w, h]，限制在 256~2048。 */
    private static int[] parseSize(String size) {
        try {
            String[] p = String.valueOf(size).toLowerCase(java.util.Locale.ROOT).split("[x*×]");
            int w = Integer.parseInt(p[0].trim());
            int h = p.length > 1 ? Integer.parseInt(p[1].trim()) : w;
            return new int[]{clampDim(w), clampDim(h)};
        } catch (Exception e) {
            return new int[]{1024, 1024};
        }
    }

    private static int clampDim(int v) {
        return Math.max(256, Math.min(2048, v));
    }

    private String generate(String prompt, String size, ImageConfig cfg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        // 固定种子（2026-10-28）：让同一段提示词的构图和人脸更稳定
        if (cfg.seed() > 0) {
            body.put("seed", cfg.seed());
        }
        // 负面提示词：压住多手多脚、画面杂乱
        if (cfg.negativePrompt() != null && !cfg.negativePrompt().isBlank()) {
            body.put("negative_prompt", cfg.negativePrompt());
        }
        // 要 URL 而不是 base64：base64 会把几百 KB 灌进上下文，模型用不上还占满窗口
        body.put("response_format", "url");

        // 用 effectiveBaseUrl：百炼地址会被补成 {origin}/compatible-mode/v1
        Request request = new Request.Builder()
                .url(cfg.imagesUrl(cfg.effectiveBaseUrl("openai")))
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

    // ---------------- 阿里云百炼（DashScope，异步两步） ----------------

    /**
     * 阿里云百炼文生图（2026-10-22）。
     *
     * <p>它是**异步**的，与 OpenAI 的一步返回不同：</p>
     * <pre>
     * POST /api/v1/services/aigc/text2image/image-synthesis   (X-DashScope-Async: enable)
     *   → {"output":{"task_id":"...","task_status":"PENDING"}}
     * GET  /api/v1/tasks/{task_id}
     *   → PENDING → RUNNING → SUCCEEDED → output.results[0].url
     * </pre>
     *
     * <p>所以必须"提交 + 轮询"。注意路径是 {@code /api/v1/...}，不是 OpenAI 的 {@code /v1/...}；
     * 尺寸用星号（{@code 1024*1024}）而不是 {@code 1024x1024}。</p>
     */
    private String generateDashScope(String prompt, String size, ImageConfig cfg) throws Exception {
        String base = dashScopeBase(cfg.effectiveBaseUrl("dashscope"));
        /*
         * 两级自适应（2026-10-22）：
         *
         * ① **异步 → 同步**：部分账号没开通异步任务，调异步直接 403
         *    "current user api does not support asynchronous calls"；
         * ② **专属域名 → 通用域名**：工作空间专属端点（{@code *.maas.aliyuncs.com}）
         *    如果只暴露了文本生成能力，文生图会返回 400 "url error, please check url"。
         *    阿里云文档也提示：Token Plan 专属 Key 不支持走文本 Base URL 调图像模型。
         */
        try {
            return dashScopeCall(prompt, size, cfg, base, true);
        } catch (IllegalStateException e) {
            String msg = lower(e.getMessage());
            if (msg.contains("asynchronous") || msg.contains("does not support async")) {
                log.warn("[image-gen] 百炼账号不支持异步调用，改用同步模式");
                try {
                    return dashScopeCall(prompt, size, cfg, base, false);
                } catch (IllegalStateException e2) {
                    return retryGenericIfUrlError(prompt, size, cfg, base, e2);
                }
            }
            return retryGenericIfUrlError(prompt, size, cfg, base, e);
        }
    }

    /**
     * 如果报的是"url error"（网关层找不到这个路径），换通用域名再试一次。
     *
     * <p>工作空间专属域名（{@code *.maas.aliyuncs.com}）不一定暴露全部能力 ——
     * 实测某 workspace 的 `/api/v1/services/aigc/text2image/...` 会回
     * 400 "url error, please check url"，而同一个 Key 换到
     * {@code https://dashscope.aliyuncs.com} 就正常。</p>
     */
    private String retryGenericIfUrlError(String prompt, String size, ImageConfig cfg,
                                          String usedBase, IllegalStateException e) throws Exception {
        String msg = lower(e.getMessage());
        boolean urlError = msg.contains("url error") || msg.contains("please check url");
        if (urlError && !DASHSCOPE_BASE.equals(usedBase)) {
            log.warn("[image-gen] 域名 {} 不接受文生图路径，改用通用域名 {} 重试", usedBase, DASHSCOPE_BASE);
            return dashScopeCall(prompt, size, cfg, DASHSCOPE_BASE, false);
        }
        throw e;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 一次百炼调用。
     *
     * @param async true = 带 {@code X-DashScope-Async} 走两步（提交 + 轮询）；
     *              false = 同步，等它直接返回结果（百炼内部有 60s 上限）
     */
    private String dashScopeCall(String prompt, String size, ImageConfig cfg,
                                 String base, boolean async) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        // 阿里云的负面词放在 input 里（不是 parameters），和 OpenAI 兼容格式不同
        if (cfg.negativePrompt() != null && !cfg.negativePrompt().isBlank()) {
            input.put("negative_prompt", cfg.negativePrompt());
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", String.valueOf(size).replace('x', '*').replace('×', '*'));
        parameters.put("n", 1);
        // 固定种子（2026-10-28）：构图与人脸更稳定
        if (cfg.seed() > 0) {
            parameters.put("seed", cfg.seed());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.effectiveModel("dashscope"));
        body.put("input", input);
        body.put("parameters", parameters);

        Request.Builder submit = new Request.Builder()
                .url(base + "/api/v1/services/aigc/text2image/image-synthesis")
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .header("User-Agent", "agent-platform/1.0");
        if (async) {
            submit.header("X-DashScope-Async", "enable");
        }

        String taskId;
        try (Response response = clientFor(cfg.timeoutMs()).newCall(submit.build()).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                /*
                 * 404 "Not support" 基本都是**地址填错**（比如填成了 /apps/anthropic、
                 * /compatible-mode/v1 这类别的能力的端点）。把猜测写进错误里，
                 * 比让用户对着一个 "Not support" 干瞪眼有用。
                 */
                String hint = response.code() == 404 || raw.contains("Not support")
                        ? "（服务地址可能不对：百炼文生图只认 origin，不能带 /apps/anthropic、"
                        + "/compatible-mode 这类路径。建议直接填 https://dashscope.aliyuncs.com）"
                        : "";
                throw new IllegalStateException("HTTP " + response.code() + " " + snippet(raw) + hint);
            }
            JsonNode root = JsonUtils.toJsonNode(raw);
            taskId = root.path("output").path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                // 同步模式（或部分模型）直接给 results
                String direct = root.path("output").path("results").path(0).path("url").asText(null);
                if (direct != null && !direct.isBlank()) {
                    return formatUrl(direct);
                }
                // 同步模式有时先回 PENDING 再转任务，这里也兜一下
                String status = root.path("output").path("task_status").asText("");
                if ("PENDING".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
                    throw new IllegalStateException("任务已受理但没有 task_id，无法轮询：" + snippet(raw));
                }
                throw new IllegalStateException("没有返回图片地址：" + snippet(raw));
            }
        }

        String queryUrl = base + "/api/v1/tasks/" + taskId;
        long deadline = System.currentTimeMillis() + cfg.timeoutMs();
        while (System.currentTimeMillis() < deadline) {
            // 出图一般 5~30 秒；2 秒一查足够，也不会顶到查询接口的 RPS 限制
            Thread.sleep(2000);
            Request query = new Request.Builder()
                    .url(queryUrl)
                    .get()
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .build();
            try (Response response = clientFor(cfg.timeoutMs()).newCall(query).execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    continue;   // 查询偶发失败不该中断整个任务，继续等下一轮
                }
                JsonNode root = JsonUtils.toJsonNode(raw);
                String status = root.path("output").path("task_status").asText("");
                if ("SUCCEEDED".equalsIgnoreCase(status)) {
                    String url = root.path("output").path("results").path(0).path("url").asText(null);
                    if (url == null || url.isBlank()) {
                        throw new IllegalStateException("任务成功但响应里没有图片地址");
                    }
                    return formatUrl(url);
                }
                if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                    String msg = root.path("output").path("message").asText("");
                    throw new IllegalStateException("任务失败：" + (msg.isBlank() ? status : msg));
                }
            }
        }
        throw new IllegalStateException("轮询超时（" + cfg.timeoutMs() + "ms）。"
                + "可以调大超时，或换更快的模型（如 wanx2.1-t2i-turbo）");
    }

    /**
     * 只保留 {@code scheme://host[:port]}，**路径全部丢掉**（2026-10-22 修）。
     *
     * <p>百炼的路径是固定的 {@code /api/v1/services/aigc/text2image/image-synthesis}，
     * 用户填的 baseUrl 里任何路径都是噪声。实测踩到过：</p>
     * <pre>
     * 用户填：https://ws-xxx.cn-beijing.maas.aliyuncs.com/apps/anthropic   ← 那是 Claude 兼容端点
     * 拼出来：.../apps/anthropic/api/v1/services/aigc/text2image/...      ← 404 "Not support"
     * </pre>
     *
     * <p>所以这里不猜"该删哪段"，直接取 origin —— 填什么都不影响。</p>
     */
    static String dashScopeBase(String baseUrl) {
        String b = baseUrl == null || baseUrl.isBlank() ? DASHSCOPE_BASE : baseUrl.trim();
        try {
            java.net.URI u = java.net.URI.create(b);
            if (u.getHost() != null && u.getScheme() != null) {
                return u.getScheme() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "");
            }
        } catch (Exception ignored) {
            /* 不是合法 URI 就按原样用，让上游报出更具体的错 */
        }
        return b.replaceAll("/+$", "");
    }

    /** 统一把图片地址包成"给模型看的一句话"。 */
    private static String formatUrl(String url) {
        return "已生成图片：\n" + url + "\n\n（请把上面这个链接原样放在回答里，用户点击即可查看）";
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
    public record ImageConfig(String apiKey, String baseUrl, String model, String size, int timeoutMs,
                              String provider, long seed, String negativePrompt) {

        static ImageConfig defaults() {
            return new ImageConfig(null, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_SIZE,
                    DEFAULT_TIMEOUT_MS, "auto", 0L, DEFAULT_NEGATIVE);
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
                    (int) clamp(num(config, CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 1000, HARD_MAX_TIMEOUT_MS),
                    blankToDefault(str(config, CFG_PROVIDER), "auto"),
                    (long) clamp(num(config, CFG_SEED, 0), 0, 2147483647L),
                    blankToDefault(str(config, CFG_NEGATIVE), DEFAULT_NEGATIVE));
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * 实际用哪家协议（2026-10-22）。
         *
         * <p>{@code auto} 的判断顺序：</p>
         * <ol>
         *   <li>没填 Key → {@code pollinations}（免费，谁都别报错）；</li>
         *   <li>地址含 {@code aliyuncs} / {@code dashscope}，或模型名以 {@code wanx} / {@code qwen-image}
         *       开头 → {@code dashscope}；</li>
         *   <li>其余 → {@code openai}（硅基流动、OpenAI、多数中转网关都是这个格式）。</li>
         * </ol>
         *
         * <p>为什么同时看地址和模型名：用户很可能只填了百炼的 Key，却忘了改"服务地址"
         * （它默认是硅基流动）。只看地址会误判成 openai，然后拿百炼的 Key 去请求硅基流动，401。</p>
         */
        public String resolveProvider() {
            String p = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
            if (!p.isEmpty() && !"auto".equals(p)) {
                return p;
            }
            if (!hasApiKey()) {
                return "pollinations";
            }
            String b = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
            String m = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
            /*
             * 百炼地址（含 compatible-mode 与否）**一律走 OpenAI 兼容模式**（2026-10-22 改）。
             *
             * <p>为什么不再默认走 DashScope 那套：`/api/v1/services/aigc/text2image/image-synthesis`
             * **只支持异步** —— 官方文档写明"HTTP 请求只支持异步，必须设置 X-DashScope-Async: enable"。
             * 而不少账号根本没开通异步（403 "current user api does not support asynchronous calls"），
             * 不带这个头又会被判成 "url error, please check url"。</p>
             *
             * <p>相比之下 `/compatible-mode/v1/images/generations` 是**同步**的、标准 OpenAI 格式，
             * 人人可用。所以 auto 一律选它；真要异步得显式把接口类型设成 dashscope。</p>
             */
            if (b.contains("aliyuncs") || b.contains("dashscope")
                    || m.startsWith("wanx") || m.startsWith("qwen-image")) {
                return "openai";
            }
            return "openai";
        }

        /** 指定协议下真正要用的地址。 */
        public String effectiveBaseUrl(String prov) {
            if ("dashscope".equals(prov) && (baseUrl == null || baseUrl.contains("siliconflow"))) {
                return DASHSCOPE_BASE;
            }
            /*
             * 百炼地址但没写 compatible-mode → 自动补上（2026-10-22）。
             *
             * 用户填的往往是 `.../apps/anthropic`（那是文本生成的兼容端点），
             * 而文生图要走 `{origin}/compatible-mode/v1`。这里帮他补，省得改配置。
             */
            if ("openai".equals(prov) && baseUrl != null
                    && (baseUrl.contains("aliyuncs") || baseUrl.contains("dashscope"))
                    && !baseUrl.contains("compatible-mode")) {
                return dashScopeBase(baseUrl) + "/compatible-mode/v1";
            }
            return baseUrl;
        }

        /** 指定协议下真正要用的模型名（同理，默认值会纠正成百炼的模型）。 */
        public String effectiveModel(String prov) {
            if ("dashscope".equals(prov) && (model == null || model.startsWith("Kwai-"))) {
                return DASHSCOPE_MODEL;
            }
            return model;
        }

        /**
         * 拼出图片生成端点。
         *
         * <p>容错用户的填法：裸域名 / 带 {@code /v1} / 带尾斜杠 / 直接填完整端点，都能正确解析
         * （与 TTS 插件的 {@code speechUrl()} 同一套规则）。</p>
         */
        public String imagesUrl() {
            return imagesUrl(baseUrl);
        }

        /** 用指定地址拼端点（让调用方能传 {@link #effectiveBaseUrl} 纠正过的值）。 */
        public String imagesUrl(String baseUrlIn) {
            String base = baseUrlIn == null ? DEFAULT_BASE_URL : baseUrlIn.trim().replaceAll("/+$", "");
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
