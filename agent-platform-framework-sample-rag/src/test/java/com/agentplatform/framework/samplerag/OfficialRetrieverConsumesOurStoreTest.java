package com.agentplatform.framework.samplerag;

import com.agentplatform.framework.rag.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E4 的对外证明（不是自测，是"别人能用"）：
 * **Spring AI 官方的 {@link VectorStoreDocumentRetriever} 直接消费我们的 {@code VectorStore}**，
 * 我们这边只提供一个实现类，没有改官方任何代码、也没有让官方组件知道我们是谁。
 *
 * <p>这个测试刻意**不起 Spring 容器**：E4 的能力与容器无关，用最少的依赖就能验证。</p>
 */
class OfficialRetrieverConsumesOurStoreTest {

    @Test
    void 官方检索器应能检索到我们存入的文档() {
        InMemoryVectorStore store = new InMemoryVectorStore(new KeywordEmbeddingModel());
        store.add(List.of(
                Document.builder().id("d1").text("苹果是一种水果").metadata(Map.of("kb", "food")).build(),
                Document.builder().id("d2").text("香蕉是黄色的").metadata(Map.of("kb", "food")).build(),
                Document.builder().id("d3").text("苹果手机很贵").metadata(Map.of("kb", "tech")).build()));

        // 官方组件，零改动直接接我们的实现
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(store)
                .topK(2)
                .similarityThreshold(0.5)
                .build();

        List<Document> hits = retriever.retrieve(new Query("苹果"));

        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(d -> d.getText().contains("苹果")),
                "检索结果应都是与「苹果」相关的文档，实际=" + hits.stream().map(Document::getText).toList());
    }

    @Test
    void 官方检索器应能按元数据过滤() {
        InMemoryVectorStore store = new InMemoryVectorStore(new KeywordEmbeddingModel());
        store.add(List.of(
                Document.builder().id("d1").text("苹果是一种水果").metadata(Map.of("kb", "food")).build(),
                Document.builder().id("d3").text("苹果手机很贵").metadata(Map.of("kb", "tech")).build()));

        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(store)
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression(new org.springframework.ai.vectorstore.filter.Filter.Expression(
                        org.springframework.ai.vectorstore.filter.Filter.ExpressionType.EQ,
                        new org.springframework.ai.vectorstore.filter.Filter.Key("kb"),
                        new org.springframework.ai.vectorstore.filter.Filter.Value("tech")))
                .build();

        List<Document> hits = retriever.retrieve(new Query("苹果"));

        assertEquals(1, hits.size());
        assertEquals("tech", hits.get(0).getMetadata().get("kb"));
    }

    /** 关键词维度模型：只为断言可控。 */
    private static class KeywordEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("桩只实现 embed");
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
