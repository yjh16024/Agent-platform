package com.agentplatform.model.repository;

import com.agentplatform.model.entity.UserFactCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 待确认画像（自动抽取候选）仓储。
 *
 * <p><b>所有查询都强制带 {@code tenantId + userId}</b> —— 与 {@link UserFactRepository} 同一约定：
 * 画像类数据一旦归属搞错，就是把 A 的信息喂给 B，而隔离靠方法签名而不是靠调用方自觉
 * （所以刻意不提供"只按 candidateId 查"的方法）。</p>
 */
@Repository
public interface UserFactCandidateRepository extends JpaRepository<UserFactCandidate, Long> {

    /**
     * 待确认列表（最新的在前）。
     * <p>{@code RejectedAtIsNull} 是这里的关键：已忽略的项**留在表里作记录**（避免重复打扰），
     * 但绝不出现在用户面前。</p>
     */
    List<UserFactCandidate> findByTenantIdAndUserIdAndRejectedAtIsNullOrderByCreatedAtDesc(
            String tenantId, String userId);

    /** 待确认条数（供界面角标）。 */
    long countByTenantIdAndUserIdAndRejectedAtIsNull(String tenantId, String userId);

    /** 全部记录（含已忽略，供测试与排查用）。 */
    List<UserFactCandidate> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId);

    /**
     * upsert 用：查同一用户下某个 key 是否已有候选。
     *
     * <p>注意这里**不带** {@code RejectedAtIsNull} —— 抽取时要能查到"这个键被忽略过"，
     * 从而跳过它（若只查待确认的，就会把用户已拒绝的东西重新抽出来）。</p>
     */
    Optional<UserFactCandidate> findByTenantIdAndUserIdAndFactKey(String tenantId, String userId, String factKey);

    /** 定位单条候选（采纳 / 忽略前用），带上归属条件让"不是自己的"直接查不到。 */
    Optional<UserFactCandidate> findByCandidateIdAndTenantIdAndUserId(
            String candidateId, String tenantId, String userId);

    /** 一键清除（含已忽略记录，用户的隐私出口）。 */
    long deleteByTenantIdAndUserId(String tenantId, String userId);
}
