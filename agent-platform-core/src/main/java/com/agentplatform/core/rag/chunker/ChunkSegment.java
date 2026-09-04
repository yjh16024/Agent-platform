package com.agentplatform.core.rag.chunker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切分结果段（中间模型，未持久化）。
 *
 * @param seqNo    切分序号
 * @param content  文本内容
 * @param metadata 元数据（页码/标题/表格等，供引用溯源）
 */
public record ChunkSegment(
        int seqNo,
        String content,
        Map<String, Object> metadata
) {
    public static ChunkSegment of(int seqNo, String content) {
        return new ChunkSegment(seqNo, content, new LinkedHashMap<>());
    }

    public static ChunkSegment of(int seqNo, String content, Map<String, Object> metadata) {
        return new ChunkSegment(seqNo, content, metadata);
    }
}