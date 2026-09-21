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

    // ------------------------------------------------------------------ 统计报表用
    // 说明：分组只按**结构化列**（category / level / agentId / timestamp）做，
    // 绝不解析 message —— ObservabilityService 里那套正则抠 latency/tokens 的方式很脆，
    // 作为"统计"的依据不合适（日志文案一改就全错，而且错得无声无息）。
    //
    // 另外**刻意不写 DATE() 之类的日期函数**：MySQL 与 H2 的日期函数不同名，
    // 一旦写进去就会出现"MySQL 能跑、H2 启动即报错"的方言问题。
    // 需要按天聚合时在 Java 侧用 timestamp.toLocalDate() 分组（见 ReportService）。

    /** 按日志类别分组计数（近 N 天）。返回 [category, count]。 */
    @Query("""
            SELECT l.category, COUNT(l) FROM LogIndex l
            WHERE l.tenantId = :tenantId AND l.timestamp >= :from
            GROUP BY l.category
            ORDER BY COUNT(l) DESC
            """)
    List<Object[]> countGroupByCategory(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /** 按级别分组计数（近 N 天）。返回 [level, count]。 */
    @Query("""
            SELECT l.level, COUNT(l) FROM LogIndex l
            WHERE l.tenantId = :tenantId AND l.timestamp >= :from
            GROUP BY l.level
            ORDER BY COUNT(l) DESC
            """)
    List<Object[]> countGroupByLevel(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /** 按智能体分组计数（近 N 天），用于"哪个智能体最活跃"。返回 [agentId, count]。 */
    @Query("""
            SELECT l.agentId, COUNT(l) FROM LogIndex l
            WHERE l.tenantId = :tenantId AND l.timestamp >= :from AND l.agentId IS NOT NULL
            GROUP BY l.agentId
            ORDER BY COUNT(l) DESC
            """)
    List<Object[]> countGroupByAgent(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /**
     * 取时间窗内的 (timestamp, level) 轻量投影，供 Java 侧按天聚合。
     *
     * <p>为什么不在 SQL 里按天 group by：见上方关于日期函数方言的说明。
     * 只取两列而不是整个实体，是为了让"拉回内存"的代价可控
     * （日志有保留期，天数也被限制，量级可接受）。</p>
     */
    @Query("""
            SELECT l.timestamp, l.level FROM LogIndex l
            WHERE l.tenantId = :tenantId AND l.timestamp >= :from
            """)
    List<Object[]> findTimestampAndLevel(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /** 窗口内的日志总数。 */
    @Query("""
            SELECT COUNT(l) FROM LogIndex l
            WHERE l.tenantId = :tenantId AND l.timestamp >= :from
            """)
    long countSince(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);
}
