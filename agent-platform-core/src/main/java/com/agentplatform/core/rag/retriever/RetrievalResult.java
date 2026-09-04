package com.agentplatform.core.rag.retriever;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 检索结果（引用溯源）。
 *
 * @param chunkId  切块 ID
 * @param content  内容
 * @param score    综合得分（重排后）
 * @param source   来源文档
 * @param page     页码（如有）
 * @param metadata 元数据
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalResult(
        String chunkId,
        String content,
        double score,
        String source,
        Integer page,
        Map<String, Object> metadata
) {
}