package com.agentplatform.model.repository;

import com.agentplatform.model.entity.ToolApproval;
import com.agentplatform.model.enums.ToolApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 工具审批仓储。
 *
 * <p>与通知/画像同样的约定：**查询一律带归属条件**（{@code tenantId}，涉及个人时再加 {@code userId}），
 * 并且**不提供"只按 approvalId 查"的方法** —— 那种写法下隔离只能靠调用方在 Java 里再比一次，
 * 漏一处就是"能批准别人的操作"。把条件放进查询、"查不到"直接成为结果，才是 fail-closed。</p>
 */
@Repository
public interface ToolApprovalRepository extends JpaRepository<ToolApproval, Long> {

    /** 按「审批 ID + 租户」查（批准/拒绝时定位用）。 */
    Optional<ToolApproval> findByApprovalIdAndTenantId(String approvalId, String tenantId);

    /**
     * 按审批 ID 查（**不带租户**）。
     *
     * <p>⚠️ <b>只供跨实例唤醒使用，不要在业务代码里调它。</b>
     * 本仓储其余方法都强制带租户，是为了让"查不到别人的数据"成为查询本身的结果
     * （fail-closed）—— 这个方法是个刻意的例外，因为唤醒广播是一条**全局**信号：
     * 广播方不知道哪些实例上有人正在等待，接收方只拿到了 {@code approvalId}。
     * 用它拿到的记录只用于"唤醒本地等待者"，不做任何对外返回。</p>
     */
    Optional<ToolApproval> findByApprovalId(String approvalId);

    /** 列表（可按状态过滤；{@code status} 为 null 表示全部）。 */
    Page<ToolApproval> findByTenantIdAndStatus(String tenantId, ToolApprovalStatus status, Pageable pageable);

    Page<ToolApproval> findByTenantId(String tenantId, Pageable pageable);

    /** 待审批条数（前端角标用，只 count 不拉列表）。 */
    long countByTenantIdAndStatus(String tenantId, ToolApprovalStatus status);

    /** 某会话的待审批条数（会话列表上标"有 N 条待批"）。 */
    long countByTenantIdAndSessionIdAndStatus(String tenantId, String sessionId, ToolApprovalStatus status);

    /**
     * 过期清理：把 {@code before} 之前仍未处理的申请标记为 expired。
     *
     * <p>用 `@Modifying` 的批量 UPDATE 而不是"查出来逐条改"：待办可能很多，
     * 且这里只需要一个状态翻转，逐条加载没有意义。</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "UPDATE ToolApproval a SET a.status = com.agentplatform.model.enums.ToolApprovalStatus.expired, "
                    + "a.errorMsg = '超过保留期未处理，自动作废' "
                    + "WHERE a.status = com.agentplatform.model.enums.ToolApprovalStatus.pending "
                    + "AND a.createdAt < :before")
    int expirePendingBefore(@org.springframework.data.repository.query.Param("before") LocalDateTime before);

    /** 企业里常见的"某个人批了多少"统计用（当前未用，留作审计扩展）。 */
    long countByTenantIdAndDecidedBy(String tenantId, String decidedBy);
}
