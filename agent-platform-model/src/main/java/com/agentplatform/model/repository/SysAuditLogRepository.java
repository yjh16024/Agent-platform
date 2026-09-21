package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计记录仓储。
 *
 * <p>查询接口一律**带租户** —— 审计数据跨租户可见是严重问题（能看到别人租户谁改了什么）。</p>
 */
public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, Long> {

    /** 列表页：按时间倒序分页（配合 idx_audit_tenant_time）。 */
    Page<SysAuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** 按操作人筛（配合 idx_audit_user）。 */
    Page<SysAuditLog> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId, Pageable pageable);

    /** 按目标筛：查"这个智能体被谁改过"（配合 idx_audit_target）—— 答辩演示用。 */
    List<SysAuditLog> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String tenantId, String targetType, String targetId);

    /** 按动作关键字 + 时间范围筛（界面上的"动作"与"时间"两个筛选条件）。 */
    @Query("""
            select a from SysAuditLog a
            where a.tenantId = :tenantId
              and (:action is null or a.action like concat('%', :action, '%'))
              and (:userId is null or a.userId = :userId)
              and (:success is null or a.success = :success)
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt <= :to)
            order by a.createdAt desc
            """)
    Page<SysAuditLog> search(@Param("tenantId") String tenantId,
                             @Param("action") String action,
                             @Param("userId") String userId,
                             @Param("success") Boolean success,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             Pageable pageable);

    long countByTenantId(String tenantId);

    /**
     * 按时间整段清理（保留期策略）。
     *
     * <p>批量 JPQL 删除而不是逐条 delete：审计表会积累到几十万行，
     * 逐条删会发出同样数量的 DELETE 语句。返回删除条数供界面显示。</p>
     */
    @Modifying
    @Transactional
    @Query("delete from SysAuditLog a where a.tenantId = :tenantId and a.createdAt < :before")
    int deleteBefore(@Param("tenantId") String tenantId, @Param("before") LocalDateTime before);

    // ------------------------------------------------------------------ 统计报表用
    // 审计表是**结构化**的（action/userId/roles/success/durationMs），
    // 所以它是本项目里最适合做统计的数据源 —— 比 log_index 那种文本日志可靠得多。

    /** 按动作分组计数。返回 [action, count]。 */
    @Query("""
            SELECT a.action, COUNT(a) FROM SysAuditLog a
            WHERE a.tenantId = :tenantId AND a.createdAt >= :from
            GROUP BY a.action
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> countGroupByAction(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /**
     * 按操作人分组计数。返回 [显示名, count]。
     *
     * <p>用 {@code COALESCE(username, userId)} 而不是直接按 userId 分组：
     * 报表上显示 {@code user_69iv5bik} 这种内部 ID 没人看得懂；
     * 而 username 可能为空（早期记录、演示模式），所以回落到 ID 兜底。</p>
     */
    @Query("""
            SELECT COALESCE(a.username, a.userId), COUNT(a) FROM SysAuditLog a
            WHERE a.tenantId = :tenantId AND a.createdAt >= :from AND a.userId IS NOT NULL
            GROUP BY COALESCE(a.username, a.userId)
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> countGroupByUser(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /** 按结果分组计数。返回 [success, count] —— 用于算失败率。 */
    @Query("""
            SELECT a.success, COUNT(a) FROM SysAuditLog a
            WHERE a.tenantId = :tenantId AND a.createdAt >= :from
            GROUP BY a.success
            """)
    List<Object[]> countGroupBySuccess(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);

    /** 窗口内的操作总数。 */
    @Query("""
            SELECT COUNT(a) FROM SysAuditLog a
            WHERE a.tenantId = :tenantId AND a.createdAt >= :from
            """)
    long countSince(@Param("tenantId") String tenantId, @Param("from") LocalDateTime from);
}
