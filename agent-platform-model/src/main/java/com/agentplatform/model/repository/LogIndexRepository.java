package com.agentplatform.model.repository;

import com.agentplatform.model.entity.LogIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运行日志仓储（MySQL 持久化）。
 */
@Repository
public interface LogIndexRepository extends JpaRepository<LogIndex, Long> {

    /**
     * 多维过滤查询（各条件为空表示不过滤，按时间倒序）。
     */
    @Query("""
            SELECT l FROM LogIndex l
            WHERE l.tenantId = :tenantId
              AND (:traceId IS NULL OR l.traceId = :traceId)
              AND (:runId IS NULL OR l.runId = :runId)
              AND (:agentId IS NULL OR l.agentId = :agentId)
              AND (:level IS NULL OR l.level = :level)
              AND (:category IS NULL OR l.category = :category)
              AND (:fingerprint IS NULL OR l.fingerprint = :fingerprint)
              AND (:keyword IS NULL OR :keyword = ''
                   OR LOWER(COALESCE(l.message, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY l.timestamp DESC, l.id DESC
            """)
    List<LogIndex> search(
            @Param("tenantId") String tenantId,
            @Param("traceId") String traceId,
            @Param("runId") String runId,
            @Param("agentId") String agentId,
            @Param("level") String level,
            @Param("category") String category,
            @Param("fingerprint") String fingerprint,
            @Param("keyword") String keyword);

    /**
     * 调用链瀑布图（按 trace 取时间线）。
     */
    List<LogIndex> findByTraceIdOrderByTimestampAscIdAsc(String traceId);

    /**
     * 清理超过保留期的日志（默认 30 天）。
     */
    @Modifying
    @Query("DELETE FROM LogIndex l WHERE l.timestamp < :before")
    int deleteBefore(@Param("before") LocalDateTime before);
}
