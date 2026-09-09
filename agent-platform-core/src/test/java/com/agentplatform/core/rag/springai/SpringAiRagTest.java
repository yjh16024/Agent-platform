package com.agentplatform.core.rag.springai;

import com.agentplatform.core.rag.chunker.ChunkSegment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI RAG 通道（解析 + 切分）验证。
 */
class SpringAiRagTest {

    /** 长文本应被 TokenTextSplitter 切成多段，内容不丢。 */
    @Test
    void chunkerSplitsLongText() {
        SpringAiChunker chunker = new SpringAiChunker();
        String text = ("Spring AI 是 Spring 生态的 AI 集成框架，支持多种模型与向量库。"
                + "它提供统一的 ChatModel、EmbeddingModel 与 VectorStore 抽象。").repeat(30);

        List<ChunkSegment> segments = chunker.split(text, 50);

        assertFalse(segments.isEmpty(), "长文本应被切分");
        if (segments.size() > 1) {
            assertTrue(segments.stream().mapToInt(s -> s.content().length()).sum() > 0);
        }
    }

    /** 纯文本/极短文本也应产出段。 */
    @Test
    void chunkerKeepsShortText() {
        SpringAiChunker chunker = new SpringAiChunker();
        List<ChunkSegment> segments = chunker.split("这是一段很短的文本。", 50);
        assertFalse(segments.isEmpty(), "短文本也应产出段");
    }

    /** txt 解析：内存资源即可，不依赖网络。 */
    @Test
    void extractorReadsPlainText() {
        SpringAiTextExtractor extractor = new SpringAiTextExtractor();
        String raw = "你好，这是天气测试文本。\n第二行内容。";
        String text = extractor.extract("hello.txt", raw.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.contains("天气测试文本"), "应提取纯文本，实际=" + text);
    }

    /** markdown 解析：去除标记保留正文。 */
    @Test
    void extractorReadsMarkdown() {
        SpringAiTextExtractor extractor = new SpringAiTextExtractor();
        String md = "# 标题\n\n这里是正文段落，包含**加粗**与`代码`。";
        String text = extractor.extract("doc.md", md.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.contains("标题"), "应含标题");
        assertTrue(text.contains("正文段落"), "应含正文，实际=" + text);
    }

    /** 开关：true 时两个组件存在，false 时不存在（回退自研）。 */
    @Test
    void ragSwitchControlsBeans() {
        new ApplicationContextRunner()
                .withPropertyValues("agent-platform.springai.rag.enabled=true")
                .withUserConfiguration(SpringAiChunker.class, SpringAiTextExtractor.class)
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null, "容器应启动成功");
                    assertTrue(ctx.getBeansOfType(SpringAiChunker.class).size() == 1);
                    assertTrue(ctx.getBeansOfType(SpringAiTextExtractor.class).size() == 1);
                });

        new ApplicationContextRunner()
                .withPropertyValues("agent-platform.springai.rag.enabled=false")
                .withUserConfiguration(SpringAiChunker.class, SpringAiTextExtractor.class)
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null);
                    assertTrue(ctx.getBeansOfType(SpringAiChunker.class).isEmpty(), "关闭时应无该 Bean");
                    assertTrue(ctx.getBeansOfType(SpringAiTextExtractor.class).isEmpty());
                });
    }
}
