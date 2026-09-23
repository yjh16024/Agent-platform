package com.agentplatform.model.repository;

import com.agentplatform.model.entity.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 会话仓储。
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    /**
     * 按会话 ID 查询（session_id 全局唯一）。
     */
    Optional<Session> findBySessionId(String sessionId);

    /**
     * 按租户 + 会话 ID 查询（隔离校验）。
     */
    Optional<Session> findByTenantIdAndSessionId(String tenantId, String sessionId);

    /**
     * 分页查询租户会话（可选 agent_id / user_id 过滤，按更新时间倒序）。
     */
    Page<Session> findByTenantIdAndAgentIdAndUserId(
            String tenantId, String agentId, String userId, Pageable pageable);

    /**
     * 取某用户在租户下的**全部**会话（不分页）。
     *
     * <p>向量记忆「重建索引」用：{@code Message} 上没有 {@code user_id}，
     * 消息的归属用户只能经会话推导，所以要先拿到这个用户的 sessionId 集合。</p>
     */
    java.util.List<Session> findByTenantIdAndUserId(String tenantId, String userId);

    Page<Session> findByTenantIdAndAgentId(String tenantId, String agentId, Pageable pageable);

    Page<Session> findByTenantId(String tenantId, Pageable pageable);

    /**
     * 强制刷新 updated_at（新消息落库后保持「最近活跃」排序正确）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Session s SET s.updatedAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId")
    int touch(@Param("sessionId") String sessionId);

    /** 统计报表用：租户下的会话总数。 */
    long countByTenantId(String tenantId);
}