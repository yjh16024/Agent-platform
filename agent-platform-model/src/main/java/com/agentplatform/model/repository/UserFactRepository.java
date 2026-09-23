package com.agentplatform.model.repository;

import com.agentplatform.model.entity.UserFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆（用户画像）仓储。
 *
 * <p><b>所有查询都强制带 {@code tenantId + userId}</b>：画像会进系统提示词，
 * 读错人等于把 A 的个人信息喂给 B 的对话。隔离不靠权限码，靠这里的方法签名 ——
 * 所以刻意<b>不</b>提供"只按 factId 查"的方法（同 {@code SysNotificationRepository} 的约定）。</p>
 */
@Repository
public interface UserFactRepository extends JpaRepository<UserFact, Long> {

    /** 按「租户 + 用户」取全部画像（注入与列表页共用，按 key 稳定排序便于展示）。 */
    List<UserFact> findByTenantIdAndUserIdOrderByCategoryAscFactKeyAsc(String tenantId, String userId);

    /** upsert 用：查同一用户下某个 key 是否已存在。 */
    Optional<UserFact> findByTenantIdAndUserIdAndFactKey(String tenantId, String userId, String factKey);

    /**
     * 按「factId + 租户 + 用户」查单条（编辑 / 删除前定位用）。
     *
     * <p>与查询同理：带上归属条件，让"不是自己的那条"直接查不到，而不是查到后再比对。</p>
     */
    Optional<UserFact> findByFactIdAndTenantIdAndUserId(String factId, String tenantId, String userId);

    /** 画像条数（供前端显示规模、以及测试断言）。 */
    long countByTenantIdAndUserId(String tenantId, String userId);

    /**
     * 一键清除（用户的隐私权利，设计里明确要求）。
     *
     * <p>返回删除条数，供接口回执。调用方需在事务内执行。</p>
     */
    long deleteByTenantIdAndUserId(String tenantId, String userId);
}
