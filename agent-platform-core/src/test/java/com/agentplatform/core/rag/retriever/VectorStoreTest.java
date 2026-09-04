package com.agentplatform.core.rag.retriever;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存向量存储单元测试。
 */
class VectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    @Test
    @DisplayName("相似向量检索返回高相似度结果")
    void similaritySearch() {
        store.upsert("a", new float[]{1, 0}, Map.of("kb_id", "kb1"));
        store.upsert("b", new float[]{0, 1}, Map.of("kb_id", "kb1"));
        store.upsert("c", new float[]{1, 1}, Map.of("kb_id", "kb2"));

        List<VectorStore.VectorMatch> results = store.similaritySearch(new float[]{1, 0}, 2, 0.0);
        assertEquals(2, results.size());
        assertEquals("a", results.get(0).id());       // 最相似
        assertEquals(1.0, results.get(0).score(), 1e-6);
    }

    @Test
    @DisplayName("minScore 阈值过滤")
    void minScoreFilter() {
        store.upsert("a", new float[]{1, 0}, Map.of());
        store.upsert("b", new float[]{0, 1}, Map.of());
        // 查询 [1,0]，b 的余弦相似度为 0
        List<VectorStore.VectorMatch> results = store.similaritySearch(new float[]{1, 0}, 10, 0.5);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id());
    }

    @Test
    @DisplayName("deleteByFilter 按元数据删除")
    void deleteByFilter() {
        store.upsert("a", new float[]{1, 0}, Map.of("kb_id", "kb1"));
        store.upsert("b", new float[]{0, 1}, Map.of("kb_id", "kb2"));
        store.deleteByFilter("kb_id", "kb1");
        assertEquals(1, store.size());
        assertTrue(store.similaritySearch(new float[]{1, 0}, 10, 0.9).isEmpty());
    }

    @Test
    @DisplayName("余弦相似度计算正确")
    void cosine() {
        assertEquals(1.0, InMemoryVectorStore.cosine(new float[]{1, 2}, new float[]{1, 2}), 1e-6);
        assertEquals(0.0, InMemoryVectorStore.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
    }
}