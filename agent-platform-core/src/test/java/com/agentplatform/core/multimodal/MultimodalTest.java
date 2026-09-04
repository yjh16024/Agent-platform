package com.agentplatform.core.multimodal;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.ModelCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多模态能力探测 + ContentPart + 配额 单元测试。
 */
class MultimodalTest {

    private MultimodalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MultimodalResolver();
    }

    @Test
    @DisplayName("图片 parts 探测为 VISION 能力")
    void imagePartsResolveToVision() {
        List<ContentPart> parts = List.of(
                new ContentPart.TextPart("分析这份图"),
                new ContentPart.ImagePart("fid_1", null, "image/png"));
        assertEquals(ModelCapability.VISION, resolver.resolve(parts));
        assertTrue(resolver.hasImage(parts));
    }

    @Test
    @DisplayName("音频 parts 探测为 AUDIO 能力")
    void audioPartsResolveToAudio() {
        List<ContentPart> parts = List.of(new ContentPart.AudioPart("fid_2", null));
        assertEquals(ModelCapability.AUDIO, resolver.resolve(parts));
        assertTrue(resolver.hasAudio(parts));
    }

    @Test
    @DisplayName("纯文本 parts 探测为 TEXT 能力")
    void textPartsResolveToText() {
        assertEquals(ModelCapability.TEXT, resolver.resolve(List.of(new ContentPart.TextPart("你好"))));
        assertEquals(ModelCapability.TEXT, resolver.resolve(List.of()));
    }

    @Test
    @DisplayName("ContentPart 密封接口 switch 穷尽")
    void sealedSwitchExhaustive() {
        ContentPart part = new ContentPart.ToolResultPart("calc", "42");
        String desc = switch (part) {
            case ContentPart.TextPart t -> "text";
            case ContentPart.ImagePart i -> "image";
            case ContentPart.AudioPart a -> "audio";
            case ContentPart.VideoPart v -> "video";
            case ContentPart.FilePart f -> "file";
            case ContentPart.ToolResultPart tr -> "tool:" + tr.toolName();
        };
        assertEquals("tool:calc", desc);
    }

    @Test
    @DisplayName("配额超限抛异常")
    void quotaExceeded() {
        QuotaService quota = new QuotaService();
        assertThrows(BizException.class, () -> {
            for (int i = 0; i < 11; i++) {
                quota.checkAndIncrement("t1", "model_calls", 10L);
            }
        });
        assertTrue(quota.usage("t1", "model_calls") > 10);
    }
}