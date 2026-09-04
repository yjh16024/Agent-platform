package com.agentplatform.model.repository;

import com.agentplatform.model.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档切分块仓储。
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByKbIdOrderBySeqNo(String kbId);

    List<Chunk> findByDocIdOrderBySeqNo(String docId);

    Optional<Chunk> findByChunkId(String chunkId);

    void deleteByKbId(String kbId);

    long countByKbId(String kbId);

    /**
     * 全文检索候选集（MySQL ngram FULLTEXT 索引，替代全表扫描）。
     * <p>只返回命中的候选 chunk（按相关性倒序限流），精确打分仍由
     * {@code HybridRetriever} 在召回结果上完成。ngram 分词下过短的查询
     * （如单字）可能无命中，调用方需回退到顺序扫描。</p>
     */
    @Query(value = """
            SELECT c.* FROM chunk c
            WHERE c.kb_id IN (:kbIds)
              AND MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE)
            LIMIT :limit
            """, nativeQuery = true)
    List<Chunk> fullTextSearch(
            @Param("kbIds") List<String> kbIds,
            @Param("query") String query,
            @Param("limit") int limit);

    /**
     * 删除单个文档的全部切分块。
     */
    void deleteByDocId(String docId);
}