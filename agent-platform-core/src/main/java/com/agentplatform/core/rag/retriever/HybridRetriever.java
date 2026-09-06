package com.agentplatform.core.rag.retriever;

import com.agentplatform.model.entity.Chunk;
import com.agentplatform.model.repository.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器（稠密向量 + 稀疏关键词 + 元数据过滤 + Rerank）。
 * <p>
 * 稠密检索走 VectorStore 余弦相似度，稀疏检索走 Chunk 内容关键词匹配，
 * 二者结果按加权 RRF（Reciprocal Rank Fusion）融合，再按综合分排序，
 * 附带 chunk 级引用溯源（来源文档、页码）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    /**
     * 混合检索。
     *
     * @param kbIds        知识库 ID 列表
     * @param query        查询文本
     * @param topK         返回条数
     * @param scoreThreshold 最低阈值
     * @param filter       元数据过滤条件（如 {"page": 3}）
     * @param rerank       是否启用 Rerank（重排）
     */
    public List<RetrievalResult> search(List<String> kbIds, String query, int topK,
                                        double scoreThreshold, Map<String, Object> filter, boolean rerank) {
        // ① 稠密检索（向量）——嵌入服务不可用时降级为空，仅保留关键词检索
        Map<String, Double> denseScores = new LinkedHashMap<>();
        try {
            float[] queryVec = embeddingService.embedQuery(query);
            List<VectorStore.VectorMatch> denseMatches = vectorStore.similaritySearch(queryVec, topK * 2, 0.0);
            // 按 kb_id 过滤 + 元数据过滤
            for (VectorStore.VectorMatch m : denseMatches) {
                String kbId = (String) m.metadata().get("kb_id");
                if (kbIds != null && !kbIds.isEmpty() && !kbIds.contains(kbId)) {
                    continue;
                }
                if (!matchesFilter(m.metadata(), filter)) {
                    continue;
                }
                denseScores.put(m.id(), m.score());
            }
        } catch (Exception e) {
            log.warn("Dense retrieval skipped (embedding unavailable), keyword search only: {}", e.getMessage());
        }

        // ② 稀疏检索（关键词）
        Map<String, Double> sparseScores = sparseSearch(kbIds, query, filter);

        // ③ 融合（RRF：rank 相加）
        Map<String, Integer> denseRank = rank(denseScores);
        Map<String, Integer> sparseRank = rank(sparseScores);

        Map<String, Double> fused = new HashMap<>();
        for (String id : denseRank.keySet()) {
            fused.put(id, fused.getOrDefault(id, 0.0) + 1.0 / (60 + denseRank.get(id)));
        }
        for (String id : sparseRank.keySet()) {
            fused.put(id, fused.getOrDefault(id, 0.0) + 1.0 / (60 + sparseRank.get(id)));
        }

        // ④ 组装结果
        List<RetrievalResult> collected = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            chunksById(e.getKey()).ifPresent(chunk -> {
                double score = normalize(e.getValue());
                if (score >= scoreThreshold) {
                    collected.add(toResult(chunk, score));
                }
            });
        }

        // ⑤ Rerank（重排：按 score 降序，模拟 Cross-encoder 精排）
        List<RetrievalResult> results = collected.stream()
                .sorted(Comparator
                        .comparingDouble(RetrievalResult::score).reversed())
                .limit(rerank ? Math.max(topK, 3) : topK)
                .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                .toList();

        return results.stream().limit(topK).toList();
    }

    /**
     * 稀疏检索：基于关键词在 chunk 内容中的命中率打分（BM25 近似）。
     * <p>
     * <b>性能优化</b>：先走 MySQL ngram FULLTEXT 索引取候选集（避免全表扫描），
     * 仅在索引无命中（如单字查询、ngram 最小粒度限制）时回退到顺序扫描，
     * 保证小知识库与演示场景依然有正确结果。
     * </p>
     */
    private Map<String, Double> sparseSearch(List<String> kbIds, String query, Map<String, Object> filter) {
        Map<String, Double> result = new LinkedHashMap<>();
        // 提取查询关键词（按空格/标点切分，过滤停用词）
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return result;
        }
        List<Chunk> candidates = fullTextCandidates(kbIds, query);
        for (Chunk chunk : candidates) {
            if (!matchesFilter(chunk.getMeta(), filter)) {
                continue;
            }
            String content = chunk.getContent() == null ? "" : chunk.getContent().toLowerCase();
            int hits = 0;
            for (String term : terms) {
                if (content.contains(term)) {
                    hits++;
                }
            }
            if (hits > 0) {
                // 命中率 + 小权重长度归一
                double score = ((double) hits / terms.size()) * 0.8
                        + 0.2 * Math.min(1.0, terms.size() / (content.length() + 1.0));
                result.put(chunk.getChunkId(), score);
            }
        }
        return result;
    }

    /**
     * 获取稀疏检索候选集：优先 FULLTEXT，回退全量扫描。
     * <p>
     * 注意：native FULLTEXT 查询的实体映射不可靠（@Lob content / JSON meta 可能不被填充，
     * 导致后续关键词匹配恒为 0）。因此这里只把 FULLTEXT 结果当作「候选 ID 清单」，
     * 内容一律经 Spring Data 派生查询 {@link #chunkRepository#findByChunkId} 回查，
     * 保证与浏览页读到的内容一致。
     * </p>
     */
    private List<Chunk> fullTextCandidates(List<String> kbIds, String query) {
        Map<String, Chunk> ordered = new LinkedHashMap<>();
        boolean fullTextOk = false;
        try {
            List<Chunk> hit = chunkRepository.fullTextSearch(kbIds, query, 500);
            if (hit != null && !hit.isEmpty()) {
                fullTextOk = true;
                // 只取 FULLTEXT 精筛出的 chunkId，再回查完整实体（去重、保持相关性顺序）
                for (Chunk c : hit) {
                    if (c.getChunkId() == null) {
                        continue;
                    }
                    chunkRepository.findByChunkId(c.getChunkId()).ifPresent(found -> ordered.putIfAbsent(found.getChunkId(), found));
                }
            }
        } catch (Exception e) {
            log.warn("Full-text search unavailable, fallback to sequential scan: {}", e.getMessage());
        }
        // FULLTEXT 无命中（或仅剩缺内容的脏行）→ 顺序扫描兜底
        if (!fullTextOk || ordered.isEmpty()) {
            for (String kbId : kbIds) {
                chunkRepository.findByKbIdOrderBySeqNo(kbId)
                        .forEach(c -> ordered.putIfAbsent(c.getChunkId(), c));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /**
     * 简单分词（中文按字、英文按词混合）。
     */
    private List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        String lower = text.toLowerCase();
        // 提取英文单词 + 中文单字
        List<String> terms = new ArrayList<>();
        for (String w : lower.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (w.isBlank()) {
                continue;
            }
            if (w.matches("[a-z0-9]+")) {
                if (w.length() >= 2) {
                    terms.add(w);
                }
            } else {
                // 中文：取 2-gram
                for (int i = 0; i < w.length() - 1; i++) {
                    terms.add(w.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    /**
     * 转排序（score 降序 → rank 从 1 开始）。
     */
    private Map<String, Integer> rank(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int rank = 1;
        for (Map.Entry<String, Double> e : sorted) {
            ranks.put(e.getKey(), rank++);
        }
        return ranks;
    }

    /**
     * RRF 分数归一化到 0~1。
     */
    private double normalize(double rrf) {
        // RRF 分数范围约 0 ~ 2/60，归一化
        return Math.min(1.0, rrf * 30.0);
    }

    /**
     * 元数据过滤匹配。
     */
    private boolean matchesFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Object v = metadata.get(e.getKey());
            if (v == null || !v.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private java.util.Optional<Chunk> chunksById(String chunkId) {
        return chunkRepository.findByChunkId(chunkId);
    }

    private RetrievalResult toResult(Chunk chunk, double score) {
        Integer page = null;
        if (chunk.getMeta() != null && chunk.getMeta().get("page") instanceof Number n) {
            page = n.intValue();
        }
        return new RetrievalResult(
                chunk.getChunkId(), chunk.getContent(), round(score), chunk.getDocId(), page, chunk.getMeta());
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}