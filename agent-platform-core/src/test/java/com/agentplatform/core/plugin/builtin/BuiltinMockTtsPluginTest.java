package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.model.HookPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinMockTtsPlugin} 的测试。
 *
 * <h3>为什么值得测</h3>
 * 这个插件的存在意义是「让 {@code extras.audio_url} 这条约定保持有消费方、可被验证」。
 * 如果它自己返回的东西不是合法音频，那就等于<b>用一个坏实现占住了位置</b> ——
 * 比没有实现更糟（前者掩盖问题，后者至少是显式缺失）。
 *
 * <p>所以关键那条测试不满足于"字符串以 data: 开头"，而是<b>交给 JDK 的
 * {@link AudioSystem} 真正解析一遍</b>：能解出正确的采样率/声道/位深与帧数，
 * 才说明前端 {@code <audio src>} 拿到它真的能播。</p>
 */
class BuiltinMockTtsPluginTest {

    private static final String DATA_URI_PREFIX = "data:audio/wav;base64,";

    private final BuiltinMockTtsPlugin plugin = new BuiltinMockTtsPlugin();

    @Test
    @DisplayName("插件元信息与钩子点正确（供市场页展示、供 registrar 登记）")
    void metadata() {
        assertEquals("builtin_mock_tts", plugin.id());
        assertEquals("1.0.0", plugin.version());
        assertEquals(HookPoint.after_llm, plugin.point(), "必须是 after_llm —— audio_url 只能经附加产物通道传出");
        assertNotNull(plugin.name());
        assertTrue(plugin.name().contains("MOCK"), "展示名必须自报是占位实现，避免被当成真实语音服务");
        assertNotNull(plugin.description());
    }

    @Test
    @DisplayName("invoke 返回 extras：含 audio_url 与 mock 标记")
    void extrasShape() {
        Object result = plugin.invoke(null);

        assertInstanceOf(Map.class, result, "after_llm 必须返回 Map 才会被当作附加产物");
        Map<?, ?> extras = (Map<?, ?>) result;
        assertTrue(extras.containsKey("audio_url"), "key 必须是 audio_url —— 宿主按此 key 取值");
        assertEquals(Boolean.TRUE, extras.get("mock"), "必须带 mock 标记");
        assertNotNull(extras.get("audio_url"));
    }

    @Test
    @DisplayName("audio_url 指向真正可解析的 WAV（交给 JDK 音频解析器验证）")
    void audioIsDecodableWav() throws Exception {
        Map<?, ?> extras = (Map<?, ?>) plugin.invoke(null);
        String uri = (String) extras.get("audio_url");
        assertTrue(uri.startsWith(DATA_URI_PREFIX), "必须是 wav 的 data URI：离线可播、不依赖外部服务");

        byte[] wav = Base64.getDecoder().decode(uri.substring(DATA_URI_PREFIX.length()));

        // ① 结构校验：四个块的标识符必须就位
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));

        // ② 语义校验：JDK 能解出格式与帧数 —— 这一步才真正证明"前端能播"
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat format = in.getFormat();
            assertEquals(8000f, format.getSampleRate(), "采样率");
            assertEquals(1, format.getChannels(), "单声道");
            assertEquals(8, format.getSampleSizeInBits(), "8-bit PCM");
            assertTrue(in.getFrameLength() > 0, "必须有实际音频帧（不能是个空壳 WAV）");
        }
    }

    @Test
    @DisplayName("音频只生成一次：多次取用是同一份（避免每轮对话重复构造）")
    void audioIsCached() {
        assertEquals(plugin.audioDataUri(), plugin.audioDataUri());
        assertEquals(plugin.audioDataUri(), plugin.audioDataUri());
    }

    @Test
    @DisplayName("生命周期方法可安全调用（无状态插件）")
    void lifecycle() {
        plugin.onAttach(null);
        plugin.onDetach(null);
        // 生命周期不改变行为
        assertNotNull(plugin.invoke(null));
    }
}
