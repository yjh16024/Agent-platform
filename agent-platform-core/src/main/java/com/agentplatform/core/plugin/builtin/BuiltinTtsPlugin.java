package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.model.HookPoint;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置插件：<b>文本转语音（TTS）</b>。
 *
 * <h3>它做什么</h3>
 * 每轮回复结束后，把助手这次的答复合成为语音，作为附加产物挂在
 * {@code extras.audio_url} 上（{@code data:audio/...;base64,...}），前端据此播放。
 * 链路：
 *
 * <pre>
 *   AgentHook(after_llm) 返回 extras.audio_url
 *     → AgentPipeline.extras() / afterStream()
 *     → AgentRunResponse.Output.audioUrl（非流式）
 *       或 RunStreamEvent.completed 帧的 audioUrl（流式）
 *     → 前端播放
 * </pre>
 *
 * <h3>为什么用「OpenAI 兼容协议」而不是绑死某一家</h3>
 * 请求体是 {@code {model, input, voice, response_format, speed}}、响应是原始音频字节 ——
 * 这是 OpenAI {@code /v1/audio/speech} 的事实标准，<b>硅基流动、OpenAI、以及大量自建/中转网关都照它实现</b>。
 * 因此本插件只实现这一套协议，换服务商时用户<b>只改 baseUrl / model / voice 三个配置项</b>，
 * 不需要改代码、也不需要平台发版。
 *
 * <p>默认值指向硅基流动（{@code FunAudioLLM/CosyVoice2-0.5B}）—— 若平台已经配了硅基流动的
 * 模型密钥，用户把同一个 Key 填进来即可直接出声，不必再注册新服务商。</p>
 *
 * <h3>★ 没配 API Key 时会怎样（刻意的设计）</h3>
 * 仍然产出音频 —— 但那是<b>一段占位音</b>（440Hz 短音），并带 {@code mock=true} 标记。
 * 这样做的理由：<b>链路本身需要是可验证的</b>。若未配置就干脆不产出任何东西，用户看到的
 * 是"我挂了这个插件但什么都没发生"，无法区分「配置没生效」与「本来是好的、只是我没填 Key」。
 * 占位音 + 明确的 {@code mock} 标记让这两种情况在界面上一眼可分。
 *
 * <h3>失败与超时：绝不影响正文</h3>
 * 任何异常（网络、401、限流、超时）都<b>只记日志、返回 null</b>（不产出 audio_url）。
 * 语音是附加产物 —— 为它让整轮对话失败是本末倒置。
 */
@Slf4j
@Component
public class BuiltinTtsPlugin implements AgentHook, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_tts";

    /** 返回值为 extras 的 key —— 必须与 AgentRuntimeService 读取的一致。 */
    public static final String EXTRA_AUDIO_URL = "audio_url";

    // ---------------- 配置键（与前端表单字段一一对应） ----------------

    public static final String CFG_API_KEY = "apiKey";
    public static final String CFG_BASE_URL = "baseUrl";
    public static final String CFG_MODEL = "model";
    public static final String CFG_VOICE = "voice";
    public static final String CFG_FORMAT = "format";
    public static final String CFG_SPEED = "speed";
    public static final String CFG_MAX_CHARS = "maxChars";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    // ---------------- 默认值：硅基流动，开箱可用 ----------------

    private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    private static final String DEFAULT_VOICE = "FunAudioLLM/CosyVoice2-0.5B:alex";
    private static final String DEFAULT_FORMAT = "mp3";
    private static final double DEFAULT_SPEED = 1.0;

    /**
     * 单轮合成字符上限。
     *
     * <p>取值理由：音频以 base64 内联在响应里，<b>体积约为「字符数 × 1KB」量级</b>。
     * 150 个汉字约合 30~60 秒语音、base64 后约 400 KB —— 这是"听起来完整"与
     * "不把响应体撑成几 MB"之间的平衡点。用户可在挂载配置里调大，
     * 但调大前请想清楚：每一轮对话都会带上这么大一坨数据。</p>
     */
    private static final int DEFAULT_MAX_CHARS = 150;

    /**
     * 合成超时。
     *
     * <p>合成是<b>同步</b>发生的（挂在收尾链路上），所以这个值直接等于"用户要多等多久"。
     * 8 秒是"给网络与厂商留足时间"与"别让用户以为卡死"的折中；超时即放弃本轮音频。
     * 注意流式下文本早已显示完，这段等待只体现为"本轮尚未结束"。</p>
     */
    private static final int DEFAULT_TIMEOUT_MS = 8000;

    private static final int HARD_MAX_CHARS = 2000;
    private static final int HARD_MAX_TIMEOUT_MS = 60_000;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * 按 agentId 隔离的配置。
     *
     * <h3>★ 为什么必须分键，而不是存在实例字段里</h3>
     * 本插件是 Spring 单例，而 {@code onAttach} 是<b>按智能体</b>回调的。
     * 若把配置写进普通字段（{@code plugin-example} 里的示例插件就是那么写的），
     * 同一个插件挂到 A 和 B 上时，后挂载的会<b>覆盖</b>先挂载的 ——
     * 表现为「A 用自己的 Key 说话，突然改成了 B 的声音和账号」。
     * 用 agentId 分键后，每个智能体各用各的配置，互不干扰。
     */
    private final Map<String, TtsConfig> configByAgent = new ConcurrentHashMap<>();

    /** OkHttpClient 按超时值缓存：OkHttp 的客户端本身就该复用（内部持有连接池与线程池）。 */
    private final Map<Integer, OkHttpClient> clientsByTimeout = new ConcurrentHashMap<>();

    /**
     * 未配置密钥时给出的占位音频（构造期生成一次，不可变常量、多线程安全共享）。
     * 与改造前的 mock 实现完全一致：8kHz / 单声道 / 8-bit PCM 的 440Hz 短音、带淡出。
     */
    private final String mockDataUri = buildMockWavDataUri();

    // ---------------- Plugin ----------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "2.0.0";
    }

    // ---------------- PluginDescriptor：市场卡片上的展示信息 ----------------

    @Override
    public String name() {
        return "文本转语音（TTS）";
    }

    @Override
    public String description() {
        return "把每轮回复合成为语音，前端可直接播放。走 OpenAI 兼容的 /v1/audio/speech 协议，"
                + "默认对接硅基流动（CosyVoice2）—— 在挂载配置里填入 API Key 即可使用，"
                + "换服务商只需改 baseUrl / model / voice。未配置密钥时会产出一段占位音"
                + "（带 mock 标记），以便区分「没配」与「配了但坏了」。";
    }

    /**
     * 读取并缓存该智能体的配置。
     *
     * <p>配置只在挂载时刻可见（{@code HookContext} 里没有 config），所以必须在这里解析并留存 ——
     * 钩子每次执行时拿不到配置。</p>
     */
    @Override
    public void onAttach(PluginContext ctx) {
        TtsConfig cfg = TtsConfig.of(ctx.config());
        configByAgent.put(ctx.agentId(), cfg);
        log.info("[tts] 已挂载到 agent={} baseUrl={} model={} voice={} 已配置密钥={}",
                ctx.agentId(), cfg.baseUrl(), cfg.model(), cfg.voice(), cfg.hasApiKey());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        configByAgent.remove(ctx.agentId());
    }

    // ---------------- 贡献：after_llm 钩子 ----------------

    @Override
    public HookPoint point() {
        return HookPoint.after_llm;
    }

    /**
     * 合成语音并作为附加产物返回。<b>不改变回复正文</b>。
     *
     * @return extras：{@code audio_url}（data URI）；失败、无文本可合成、或未配置且强制关闭时返回 null
     */
    @Override
    public Object invoke(HookContext ctx) {
        String text = ctx.input() == null ? "" : String.valueOf(ctx.input());
        if (text.isBlank()) {
            // 没有正文就没什么可念的（例如工具轮里模型只发了工具调用）
            return null;
        }

        TtsConfig cfg = configByAgent.getOrDefault(ctx.agentId(), TtsConfig.defaults());
        if (!cfg.hasApiKey()) {
            // 未配置密钥：产出占位音，让"插件生效了"这件事在界面上可被看见
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put(EXTRA_AUDIO_URL, mockDataUri);
            extras.put("mock", Boolean.TRUE);
            extras.put("plugin", PLUGIN_ID);
            extras.put("tts_hint", "未配置 API Key：当前为占位音。在「插件 → 挂载配置」中填入密钥后即可合成真实语音。");
            return extras;
        }

        try {
            return synthesize(text, cfg);
        } catch (Exception e) {
            // 语音是附加产物：合成失败绝不能让这一轮对话失败
            log.warn("[tts] 合成失败（本轮不产出音频）agent={} reason={}", ctx.agentId(), e.getMessage());
            return null;
        }
    }

    /**
     * 调用 OpenAI 兼容的 {@code /v1/audio/speech}，把音频转成 data URI。
     */
    private Map<String, Object> synthesize(String rawText, TtsConfig cfg) throws Exception {
        boolean truncated = rawText.length() > cfg.maxChars();
        String text = truncated ? rawText.substring(0, cfg.maxChars()) : rawText;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("input", text);
        body.put("voice", cfg.voice());
        body.put("response_format", cfg.format());
        body.put("speed", cfg.speed());

        Request request = new Request.Builder()
                .url(cfg.speechUrl())
                .post(RequestBody.create(json(body), JSON))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Accept", "audio/*")
                .header("User-Agent", "agent-platform/1.0")
                .build();

        byte[] audio;
        try (Response response = clientFor(cfg.timeoutMs()).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() == null ? "" : snippet(response.body().string());
                throw new IllegalStateException("TTS 服务返回 HTTP " + response.code() + " " + err);
            }
            if (response.body() == null) {
                throw new IllegalStateException("TTS 服务返回空响应体");
            }
            audio = response.body().bytes();
        }
        if (audio.length == 0) {
            throw new IllegalStateException("TTS 服务返回了 0 字节音频");
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(EXTRA_AUDIO_URL, "data:" + mimeOf(cfg.format()) + ";base64,"
                + Base64.getEncoder().encodeToString(audio));
        extras.put("plugin", PLUGIN_ID);
        extras.put("format", cfg.format());
        extras.put("chars", text.length());
        if (truncated) {
            // 让前端/日志能解释"为什么只念了前半段"
            extras.put("truncated", Boolean.TRUE);
            extras.put("full_chars", rawText.length());
        }
        log.info("[tts] 已合成 agent={} chars={}{} bytes={}",
                cfg.voice(), text.length(), truncated ? "(截断)" : "", audio.length);
        return extras;
    }

    private OkHttpClient clientFor(int timeoutMs) {
        return clientsByTimeout.computeIfAbsent(timeoutMs, ms -> new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.min(ms, 6000)))
                .readTimeout(Duration.ofMillis(ms))
                .writeTimeout(Duration.ofMillis(ms))
                .build());
    }

    /**
     * 音频 MIME 映射。
     *
     * <p>注意 {@code mp3} 的标准 MIME 是 {@code audio/mpeg}，写成 {@code audio/mp3} 浏览器不认 ——
     * 而"不认"的表现是<b>静默不播放</b>，排查起来很费劲。</p>
     */
    private static String mimeOf(String format) {
        return switch (format == null ? "" : format.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "opus" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/wav";
            default -> "audio/mpeg";
        };
    }

    /** 供测试与诊断用的占位音频。 */
    public String mockAudioDataUri() {
        return mockDataUri;
    }

    private static String json(Object value) {
        return com.agentplatform.common.util.JsonUtils.toJson(value);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ---------------- 配置模型 ----------------

    /**
     * 一次挂载的 TTS 配置快照（不可变）。
     *
     * @param apiKey    服务密钥；空表示未配置（走占位音）
     * @param baseUrl   服务地址（OpenAI 兼容，通常以 {@code /v1} 结尾）
     * @param model     语音合成模型
     * @param voice     音色
     * @param format    音频格式（mp3 / wav / opus ...）
     * @param speed     语速
     * @param maxChars  单轮合成字符上限（成本与响应体积的唯一闸门）
     * @param timeoutMs 合成超时
     */
    public record TtsConfig(String apiKey, String baseUrl, String model, String voice,
                            String format, double speed, int maxChars, int timeoutMs) {

        /** 未挂载任何智能体时的默认配置（仅用于兜底，正常路径不会有）。 */
        static TtsConfig defaults() {
            return new TtsConfig(null, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_VOICE,
                    DEFAULT_FORMAT, DEFAULT_SPEED, DEFAULT_MAX_CHARS, DEFAULT_TIMEOUT_MS);
        }

        public static TtsConfig of(JsonNode config) {
            if (config == null || config.isNull()) {
                return defaults();
            }
            return new TtsConfig(
                    blankToNull(str(config, CFG_API_KEY)),
                    blankToDefault(str(config, CFG_BASE_URL), DEFAULT_BASE_URL),
                    blankToDefault(str(config, CFG_MODEL), DEFAULT_MODEL),
                    blankToDefault(str(config, CFG_VOICE), DEFAULT_VOICE),
                    blankToDefault(str(config, CFG_FORMAT), DEFAULT_FORMAT),
                    clamp(num(config, CFG_SPEED, DEFAULT_SPEED), 0.25, 4.0),
                    (int) clamp(num(config, CFG_MAX_CHARS, DEFAULT_MAX_CHARS), 1, HARD_MAX_CHARS),
                    (int) clamp(num(config, CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 500, HARD_MAX_TIMEOUT_MS));
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * 拼出语音合成端点。
         *
         * <p>容错用户填法：{@code https://api.siliconflow.cn}、{@code .../v1}、
         * {@code .../v1/} 都能正确拼成 {@code .../v1/audio/speech}；
         * 若用户直接填了完整端点（以 {@code /audio/speech} 结尾）则原样使用。</p>
         */
        public String speechUrl() {
            String base = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
            if (base.endsWith("/audio/speech")) {
                return base;
            }
            if (!base.endsWith("/v1")) {
                base = base + "/v1";
            }
            return base + "/audio/speech";
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

    // ---------------- 占位音频构造（沿用改造前的实现） ----------------

    private static final int SAMPLE_RATE = 8000;
    /** 0.4 秒：够听出"有声音"，又不会让 base64 撑大响应体（约 4.3 KB）。 */
    private static final int SAMPLE_COUNT = (int) (SAMPLE_RATE * 0.4);
    private static final double TONE_HZ = 440.0;

    /**
     * 生成一段 8kHz / 单声道 / 8-bit PCM 的正弦波，封装为标准 WAV 并转成 data URI。
     *
     * <p>选 8-bit 无符号 PCM 是因为它最简单：无需处理字节序与有符号偏移，
     * 而且 8kHz 单声道足够短小。加线性淡出是为了避免结尾处波形被硬切产生爆音。</p>
     */
    private static String buildMockWavDataUri() {
        byte[] pcm = new byte[SAMPLE_COUNT];
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double t = (double) i / SAMPLE_RATE;
            double envelope = 1.0 - (double) i / SAMPLE_COUNT;
            double sample = Math.sin(2 * Math.PI * TONE_HZ * t) * envelope * 0.5;
            // 8-bit PCM 是无符号：128 为静音中点，0..255 对应 -1.0..1.0
            pcm[i] = (byte) (128 + (int) Math.round(sample * 127));
        }

        byte[] wav = new byte[44 + pcm.length];
        writeAscii(wav, 0, "RIFF");
        writeInt32LE(wav, 4, 36 + pcm.length);
        writeAscii(wav, 8, "WAVE");

        writeAscii(wav, 12, "fmt ");
        writeInt32LE(wav, 16, 16);
        writeInt16LE(wav, 20, 1);
        writeInt16LE(wav, 22, 1);
        writeInt32LE(wav, 24, SAMPLE_RATE);
        writeInt32LE(wav, 28, SAMPLE_RATE);
        writeInt16LE(wav, 32, 1);
        writeInt16LE(wav, 34, 8);

        writeAscii(wav, 36, "data");
        writeInt32LE(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);

        return "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
    }

    private static void writeAscii(byte[] buf, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            buf[offset + i] = (byte) text.charAt(i);
        }
    }

    private static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }

    private static void writeInt16LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }
}
