package com.agentplatform.core.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切分器（策略模式）。
 * <p>
 * 基于句子边界（。！？.!?）聚合为语义块：以句号等强分隔符为锚点，
 * 相邻句子按块大小上限归并，避免在句子中间切断语义。
 * </p>
 */
@Component("semantic")
public class SemanticChunker extends Chunker {

    @Override
    public String strategy() {
        return "semantic";
    }

    @Override
    protected List<ChunkSegment> doSplit(String text, ChunkConfig config) {
        // 按强分隔符将文本拆为句子
        List<String> sentences = splitSentences(text);

        List<ChunkSegment> segments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int seq = 0;
        for (String sentence : sentences) {
            if (buffer.length() + sentence.length() > config.chunkSize() && buffer.length() > 0) {
                segments.add(ChunkSegment.of(seq++, buffer.toString().trim()));
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            segments.add(ChunkSegment.of(seq, buffer.toString().trim()));
        }
        return segments;
    }

    /**
     * 按句号/问号/感叹号等拆句（保留分隔符）。
     */
    private List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n') {
                result.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            result.add(sb.toString());
        }
        return result;
    }
}