package com.agentplatform.core.rag.chunker;

import java.util.List;

/**
 * 文档切分器基类（模板方法模式，对应 §5.3）。
 * <p>
 * 切分骨架（清洗 → 切分 → enrich）固定在抽象基类（final 防篡改），
 * 具体切分算法（递归字符 / 语义 / 结构感知）作为策略可插拔。
 * </p>
 */
public abstract class Chunker {

    /**
     * 切分策略名称（对应 knowledge_base.chunk_strategy）。
     */
    public abstract String strategy();

    /**
     * 模板方法：固定切分流程，final 防篡改。
     *
     * @param text   清洗后的文本
     * @param config 切分配置
     */
    public final List<ChunkSegment> chunk(String text, ChunkConfig config) {
        // ① 清洗（模板方法默认实现，可覆写）
        String cleaned = clean(text);
        // ② 切分（钩子：子类实现具体算法）
        List<ChunkSegment> segments = doSplit(cleaned, config);
        // ③ enrich（模板方法默认实现，可覆写）
        return enrich(segments);
    }

    /**
     * 具体切分算法（钩子方法，子类实现）。
     */
    protected abstract List<ChunkSegment> doSplit(String text, ChunkConfig config);

    /**
     * 文本清洗（默认实现，去除多余空白与全角空格）。
     */
    protected String clean(String text) {
        if (text == null) {
            return "";
        }
        // 归一化换行 + 压缩连续空白
        return text.replace("\r\n", "\n")
                .replace(" ", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 富化（默认实现，补齐 seqNo 与元数据结构）。
     */
    protected List<ChunkSegment> enrich(List<ChunkSegment> segments) {
        return segments;
    }

    /**
     * 切分配置。
     *
     * @param chunkSize    块大小（字符数）
     * @param chunkOverlap 块重叠（字符数）
     * @param separators   分隔符优先级（递归切分用）
     */
    public record ChunkConfig(int chunkSize, int chunkOverlap, List<String> separators) {
        public static ChunkConfig defaults() {
            return new ChunkConfig(512, 50, List.of("\n\n", "\n", "。", "！", "？", "；", ".", "!", "?", ";", " "));
        }
    }
}