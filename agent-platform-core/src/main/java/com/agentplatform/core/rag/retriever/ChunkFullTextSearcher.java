package com.agentplatform.core.rag.retriever;

import com.agentplatform.model.entity.Chunk;
import com.agentplatform.model.repository.ChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * FULLTEXT 候选集检索（独立事务）。
 * <p>
 * <b>背景（本类存在的唯一原因）</b>：MySQL 的 {@code MATCH(c.content) AGAINST(:query)} 是 native 查询，
 * 在 H2（桌面内置库）下语法不兼容，执行必然抛 SQL 异常。而该异常发生时，
 * Hibernate 会把<b>外层事务</b>标记为 rollback-only；调用方即使 catch 掉异常、走顺序扫描兜底，
 * 最终提交时仍会抛
 * {@code UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only}
 * —— 检索结果其实已经算出来了，却被事务提交阶段否决。
 * </p>
 * 三层处理：
 * <ol>
 *   <li>FULLTEXT 查询放进<b>独立事务</b>（REQUIRES_NEW）：失败只回滚它自己，外层事务不被污染；</li>
 *   <li>只返回 chunkId（不返回实体）：避免跨事务脱管实体（{@code @Lob content} 可能懒加载导致
 *       LazyInitializationException），实体一律由外层事务回查；</li>
 *   <li>H2 / 内置库场景用 {@code agent-platform.rag.fulltext.enabled=false} 直接跳过，
 *       连那条必然失败的 SQL 都不执行。</li>
 * </ol>
 */
@Slf4j
@Component
public class ChunkFullTextSearcher {

    private final ChunkRepository chunkRepository;
    private final boolean enabled;

    public ChunkFullTextSearcher(ChunkRepository chunkRepository,
                                 @Value("${agent-platform.rag.fulltext.enabled:true}") boolean enabled) {
        this.chunkRepository = chunkRepository;
        this.enabled = enabled;
    }

    /** 当前环境是否启用 FULLTEXT 候选集（MySQL 为 true，H2 内置库为 false）。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 查 FULLTEXT 候选 chunkId。
     *
     * @return chunkId 列表；未启用 / 无命中时返回空列表
     * @throws RuntimeException native 查询失败时抛出（调用方负责 catch 并回退顺序扫描）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String> searchChunkIds(List<String> kbIds, String query, int limit) {
        if (!enabled) {
            return List.of();
        }
        List<Chunk> hit = chunkRepository.fullTextSearch(kbIds, query, limit);
        if (hit == null || hit.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(hit.size());
        for (Chunk c : hit) {
            if (c != null && c.getChunkId() != null) {
                ids.add(c.getChunkId());
            }
        }
        log.debug("FULLTEXT candidates: {} (kbIds={})", ids.size(), kbIds);
        return ids;
    }
}
