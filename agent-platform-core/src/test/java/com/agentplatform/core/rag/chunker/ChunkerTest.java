package com.agentplatform.core.rag.chunker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切分器单元测试（模板方法 + 策略）。
 */
class ChunkerTest {

    private final RecursiveChunker recursive = new RecursiveChunker();
    private final StructuralChunker structural = new StructuralChunker();
    private final SemanticChunker semantic = new SemanticChunker();

    private final Chunker.ChunkConfig config = Chunker.ChunkConfig.defaults();

    @Test
    @DisplayName("递归切分：空文本返回空列表")
    void recursiveEmpty() {
        assertTrue(recursive.chunk("", config).isEmpty());
        assertTrue(recursive.chunk(null, config).isEmpty());
    }

    @Test
    @DisplayName("递归切分：短文本为单段")
    void recursiveShortText() {
        List<ChunkSegment> chunks = recursive.chunk("你好，这是一个测试。", config);
        assertEquals(1, chunks.size());
        assertEquals("你好，这是一个测试。", chunks.get(0).content());
    }

    @Test
    @DisplayName("递归切分：长文本切成多段且 seqNo 递增")
    void recursiveLongText() {
        String longText = "第一段内容。" + "第二段内容。".repeat(200);
        Chunker.ChunkConfig small = new Chunker.ChunkConfig(100, 0, config.separators());
        List<ChunkSegment> chunks = recursive.chunk(longText, small);
        assertTrue(chunks.size() > 1, "should split into multiple chunks");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).seqNo());
        }
    }

    @Test
    @DisplayName("结构感知切分：识别标题并将 heading 注入元数据")
    void structuralExtractsHeading() {
        String md = "## 第一章\n这是第一章的内容。\n\n## 第二章\n这是第二章的内容。";
        List<ChunkSegment> chunks = structural.chunk(md, new Chunker.ChunkConfig(500, 0, config.separators()));
        assertEquals(2, chunks.size());
        assertEquals("第一章", chunks.get(0).metadata().get("heading"));
        assertEquals("第二章", chunks.get(1).metadata().get("heading"));
    }

    @Test
    @DisplayName("语义切分：按句子边界切分")
    void semanticBySentence() {
        String text = "句子一。句子二！句子三？";
        List<ChunkSegment> chunks = semantic.chunk(text, new Chunker.ChunkConfig(5, 0, config.separators()));
        assertTrue(chunks.size() >= 2);
    }

    @Test
    @DisplayName("策略名正确")
    void strategyNames() {
        assertEquals("recursive", recursive.strategy());
        assertEquals("structural", structural.strategy());
        assertEquals("semantic", semantic.strategy());
    }
}