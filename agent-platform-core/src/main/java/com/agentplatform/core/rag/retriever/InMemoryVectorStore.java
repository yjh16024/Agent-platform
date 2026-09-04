package com.agentplatform.core.rag.retriever;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（默认实现）。
 * <p>
 * 提供基于余弦相似度的遍历检索，适合开发/单测/演示。经
 * {@code agent-platform.rag.vector-store=milvus} 切换为 {@link MilvusVectorStore}；
 * 默认（in-memory / 未配置）生效。
 * </p>
 */
@Component
@ConditionalOnProperty(name = "agent-platform.rag.vector-store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public void upsert(String id, float[] embedding, Map<String, Object> metadata) {
        store.put(id, new Entry(embedding, metadata));
    }

    @Override
    public List<VectorMatch> similaritySearch(float[] query, int topK, double minScore) {
        List<VectorMatch> results = new ArrayList<>();
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            double score = cosine(query, e.getValue().embedding());
            if (score >= minScore) {
                results.add(new VectorMatch(e.getKey(), score, e.getValue().metadata()));
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public void deleteByFilter(String key, String value) {
        store.entrySet().removeIf(e -> value.equals(e.getValue().metadata().get(key)));
    }

    /**
     * 记录条数（测试用）。
     */
    public int size() {
        return store.size();
    }

    /**
     * 余弦相似度。
     */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            // 维度不一致时按较短维度计算
            int n = Math.min(a.length, b.length);
            return cosine(a, b, n);
        }
        return cosine(a, b, a.length);
    }

    private static double cosine(float[] a, float[] b, int n) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Entry(float[] embedding, Map<String, Object> metadata) {
    }
}