package com.agentplatform.framework.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E4 的行为验收：标准接口能按语义检索、能按 eq 过滤，且**不支持的类型必须显式失败**。
 */
class InMemoryVectorStoreTest {

    private final InMemoryVectorStore store = new InMemoryVectorStore(new KeywordEmbeddingModel());

    private static Document doc(String id, String text, String source) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", source)).build();
    }

    private void seed() {
        store.add(List.of(
                doc("d1", "苹果是一种水果", "kb-1"),
                doc("d2", "香蕉是黄色的", "kb-1"),
                doc("d3", "苹果手机很贵", "kb-2")));
    }

    @Test
    void 语义相近的文档应排在前面() {
        seed();

        List<Document> hits = store.similaritySearch(SearchRequest.builder().query("苹果").topK(2).build());

        assertEquals(2, hits.size());
        assertTrue(hits.get(0).getText().contains("苹果"), "最相关的应排第一，实际=" + hits.get(0).getText());
        assertTrue(hits.get(0).getScore() >= hits.get(1).getScore());
    }

    @Test
    void topK与阈值都应生效() {
        seed();

        assertEquals(1, store.similaritySearch(SearchRequest.builder().query("苹果").topK(1).build()).size());

        List<Document> strict = store.similaritySearch(
                SearchRequest.builder().query("苹果").topK(10).similarityThreshold(0.99).build());
        strict.forEach(d -> assertTrue(d.getScore() >= 0.99));
    }

    @Test
    void eq过滤应按元数据筛选() {
        seed();

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("苹果")
                .topK(10)
                .filterExpression(new Filter.Expression(Filter.ExpressionType.EQ,
                        new Filter.Key("source"), new Filter.Value("kb-2")))
                .build());

        assertEquals(1, hits.size());
        assertEquals("kb-2", hits.get(0).getMetadata().get("source"));
    }

    @Test
    void 非eq过滤必须显式报错而不是返回错误结果() {
        seed();

        SearchRequest request = SearchRequest.builder()
                .query("苹果")
                .filterExpression(new Filter.Expression(Filter.ExpressionType.NE,
                        new Filter.Key("source"), new Filter.Value("kb-2")))
                .build();

        UnsupportedOperationException ex =
                assertThrows(UnsupportedOperationException.class, () -> store.similaritySearch(request));
        assertTrue(ex.getMessage().contains("eq"));
    }

    @Test
    void 按id与按表达式删除都应生效() {
        seed();

        store.delete(List.of("d1"));
        assertEquals(2, store.size());

        store.delete(new Filter.Expression(Filter.ExpressionType.EQ,
                new Filter.Key("source"), new Filter.Value("kb-1")));
        assertEquals(1, store.size());
        assertEquals(1, store.similaritySearch(SearchRequest.builder().query("手机").topK(10).build()).size());
    }

    /**
     * 关键词维度模型：只为"可控断言"，不追真实性。
     */
    private static class KeywordEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("本桩只实现 embed(String/Document)");
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            if (text == null) {
                return new float[]{0, 0, 1};
            }
            if (text.contains("苹果")) {
                return new float[]{1, 0, 0};
            }
            if (text.contains("香蕉")) {
                return new float[]{0, 1, 0};
            }
            return new float[]{0, 0, 1};
        }
    }
}
