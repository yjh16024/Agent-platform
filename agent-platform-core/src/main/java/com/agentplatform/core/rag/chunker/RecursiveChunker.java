package com.agentplatform.core.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分器（策略模式）。
 * <p>
 * 按分隔符优先级递归切分，优先按段落/句子边界切，超长段按固定大小强制切分，
 * 支持 chunkOverlap 重叠以保持语义连贯。
 * </p>
 */
@Component("recursive")
public class RecursiveChunker extends Chunker {

    @Override
    public String strategy() {
        return "recursive";
    }

    @Override
    protected List<ChunkSegment> doSplit(String text, ChunkConfig config) {
        List<String> pieces = new ArrayList<>();
        splitRecursive(text, config.separators(), config.chunkSize(), pieces);

        List<ChunkSegment> segments = new ArrayList<>();
        int seq = 0;
        StringBuilder buffer = new StringBuilder();
        for (String piece : pieces) {
            if (buffer.length() + piece.length() > config.chunkSize() && buffer.length() > 0) {
                segments.add(ChunkSegment.of(seq++, buffer.toString().trim()));
                buffer.setLength(0);
            }
            buffer.append(piece);
            if (buffer.length() >= config.chunkSize()) {
                segments.add(ChunkSegment.of(seq++, buffer.toString().trim()));
                buffer.setLength(0);
            }
        }
        if (buffer.length() > 0) {
            segments.add(ChunkSegment.of(seq, buffer.toString().trim()));
        }

        // 应用 chunkOverlap（在相邻段间重叠末尾片段）
        return applyOverlap(segments, config.chunkOverlap());
    }

    /**
     * 按分隔符优先级递归切分。
     */
    private void splitRecursive(String text, List<String> separators, int chunkSize, List<String> out) {
        if (text.length() <= chunkSize) {
            if (!text.isBlank()) {
                out.add(text);
            }
            return;
        }
        // 找到第一个可用的分隔符
        String sep = null;
        int sepIndex = -1;
        for (String s : separators) {
            int idx = text.indexOf(s);
            if (idx > 0) {
                sep = s;
                sepIndex = idx;
                break;
            }
        }
        if (sep == null) {
            // 无分隔符，强制按 size 切分
            for (int start = 0; start < text.length(); start += chunkSize) {
                out.add(text.substring(start, Math.min(text.length(), start + chunkSize)));
            }
            return;
        }
        // 二分并递归
        splitRecursive(text.substring(0, sepIndex + sep.length()), separators, chunkSize, out);
        splitRecursive(text.substring(sepIndex + sep.length()), separators, chunkSize, out);
    }

    /**
     * 段间重叠。
     */
    private List<ChunkSegment> applyOverlap(List<ChunkSegment> segments, int overlap) {
        if (overlap <= 0 || segments.size() < 2) {
            return segments;
        }
        List<ChunkSegment> result = new ArrayList<>();
        result.add(segments.get(0));
        for (int i = 1; i < segments.size(); i++) {
            ChunkSegment prev = segments.get(i - 1);
            ChunkSegment cur = segments.get(i);
            String tail = prev.content().length() > overlap
                    ? prev.content().substring(prev.content().length() - overlap)
                    : prev.content();
            result.add(ChunkSegment.of(cur.seqNo(), tail + cur.content(), cur.metadata()));
        }
        return result;
    }
}