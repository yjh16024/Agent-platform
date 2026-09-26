package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.model.HookPoint;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置插件：<b>语音合成占位实现（MOCK）</b>。
 *
 * <h3>它为什么存在</h3>
 * 平台有一条贯穿全链路的音频约定，但<b>长期没有任何实现去消费它</b>：
 *
 * <pre>
 *   AgentHook(after_llm) 返回 extras.audio_url
 *     → AgentPipeline.extras()
 *     → AgentRuntimeService.run() 把 extras["audio_url"] 填进
 *       AgentRunResponse.Output.audioUrl
 *     → 前端播放
 * </pre>
 *
 * 2026-09-20 删掉旧的 {@code TtsPlugin}（当时是写死的 mock 规则与音频地址）之后，
 * 这条约定变成了<b>零消费方</b> —— 而无人消费的约定会静默腐化：某天有人改了
 * {@code extras} 的 key、或删掉 {@code Output.audioUrl}，不会有任何测试或界面报错。
 * 本插件的作用就是<b>保持这条链路是活的、可被验证的</b>。
 *
 * <h3>与"写死一个假 URL"的区别（为什么不用那种做法）</h3>
 * <ul>
 *   <li>返回的是<b>真实可解析、可播放的 WAV</b>（440Hz 短音，带淡出避免爆音），
 *       以 {@code data:audio/wav;base64,...} 内联。离线可播、不依赖任何外部服务与网络。</li>
 *   <li>因此它验证的是<b>完整链路</b>，而非只验证"有个字符串传下去了"。</li>
 *   <li>返回值里带 {@code mock=true} 标记，界面与日志都能看出这是占位实现，
 *       不会被误当成真实语音服务。</li>
 * </ul>
 *
 * <h3>接真实服务时怎么做</h3>
 * 照抄本类的钩子形态即可（返回 {@code Map} 且 key 为 {@code audio_url}），
 * 把 {@link #invoke} 里生成音频那一步换成调用厂商 SDK（Azure Speech / 阿里云智能语音…）。
 * 更完整的插件示例见 {@code plugin-example} 模块的 {@code OutputEnrichPlugin}。
 *
 * <h3>已知边界</h3>
 * <ul>
 *   <li>钩子只在<b>非流式</b>链路生效（{@code before_llm} / {@code on_error} 在流式下也生效，
 *       但 {@code after_llm} / {@code before_output} 刻意不生效 —— 流式内容已逐块推给前端，
 *       事后附加产物会造成前后端不一致）。所以本插件需<b>关闭「流式」开关</b>才看得到效果。</li>
 *   <li>音频内容固定（合成的是固定音调，不是用户文字），这是占位实现的本质，不是缺陷。</li>
 * </ul>
 */
@Component
public class BuiltinMockTtsPlugin implements AgentHook, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_mock_tts";

    /** 返回值为 extras 的 key —— 必须与 AgentRuntimeService 读取的一致。 */
    public static final String EXTRA_AUDIO_URL = "audio_url";

    private static final int SAMPLE_RATE = 8000;
    /** 0.4 秒：够听出"有声音"，又不会让 base64 撑大响应体（约 4.3 KB）。 */
    private static final int SAMPLE_COUNT = (int) (SAMPLE_RATE * 0.4);
    private static final double TONE_HZ = 440.0;

    /**
     * 音频只在首次使用时生成一次。
     * 它是不可变常量，可在多线程间安全共享（钩子会被并发调用）。
     */
    private final String cachedDataUri = buildWavDataUri();

    public String audioDataUri() {
        return cachedDataUri;
    }

    // ---------------- Plugin ----------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    // ---------------- PluginDescriptor：市场卡片上的展示信息 ----------------

    @Override
    public String name() {
        return "语音合成（MOCK 占位）";
    }

    @Override
    public String description() {
        return "占位实现：每轮回复都附加一段真实可播的 WAV（audio_url），用于保持「插件 extras.audio_url → "
                + "输出 audioUrl → 前端播放」这条链路可验证。合成的是固定音调、不是用户文字。"
                + "需关闭「流式」开关；接真实语音服务请参考本插件的钩子写法。";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        // 无状态插件：音频是常量，无需初始化
    }

    @Override
    public void onDetach(PluginContext ctx) {
        // 无状态插件：无需清理
    }

    // ---------------- 贡献：after_llm 钩子 ----------------

    @Override
    public HookPoint point() {
        return HookPoint.after_llm;
    }

    /**
     * 附加音频产物。<b>不改变回复正文</b> —— 正文要改写请用 {@code before_output}。
     *
     * @return extras：{@code audio_url}（data URI）+ {@code mock} 标记。
     * 返回值只能是可 JSON 序列化的简单类型。
     */
    @Override
    public Object invoke(HookContext ctx) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(EXTRA_AUDIO_URL, cachedDataUri);
        // 显式标记，避免被误认为是真实语音服务；前端与日志都可据此识别
        extras.put("mock", Boolean.TRUE);
        extras.put("plugin", PLUGIN_ID);
        return extras;
    }

    // ---------------- WAV 构造 ----------------

    /**
     * 生成一段 8kHz / 单声道 / 8-bit PCM 的正弦波，封装为标准 WAV 并转成 data URI。
     *
     * <p>选 8-bit 无符号 PCM 是因为它最简单：无需处理字节序与有符号偏移，
     * 而且 8kHz 单声道足够短小。加线性淡出是为了避免结尾处波形被硬切产生爆音。</p>
     */
    private static String buildWavDataUri() {
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
        writeInt32LE(wav, 4, 36 + pcm.length);   // 后续字节数
        writeAscii(wav, 8, "WAVE");

        writeAscii(wav, 12, "fmt ");
        writeInt32LE(wav, 16, 16);               // fmt 块长度
        writeInt16LE(wav, 20, 1);                // PCM 格式
        writeInt16LE(wav, 22, 1);                // 单声道
        writeInt32LE(wav, 24, SAMPLE_RATE);
        writeInt32LE(wav, 28, SAMPLE_RATE);      // byteRate = rate × channels × bits/8 = 8000
        writeInt16LE(wav, 32, 1);                // blockAlign = channels × bits/8 = 1
        writeInt16LE(wav, 34, 8);                // 位深

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

    /** 小端 32 位（WAV 规定小端）。 */
    private static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }

    /** 小端 16 位。 */
    private static void writeInt16LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }
}
