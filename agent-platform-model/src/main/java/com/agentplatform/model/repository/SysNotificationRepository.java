package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysNotification;
import com.agentplatform.model.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 站内通知仓储。
 *
 * <p>所有查询都强制带 {@code tenantId} + {@code recipientId}：通知是**按人投递**的数据，
 * 缺任何一个都可能把别人的通知读出来。收件人隔离不靠权限码，靠这里的方法签名。</p>
 */
@Repository
public interface SysNotificationRepository extends JpaRepository<SysNotification, Long> {

    /**
     * 按「通知 ID + 租户 + 收件人」三要素查（**收件人隔离做在 SQL 层**）。
     *
     * <p>刻意<b>不</b>提供"只按 notificationId 查"的方法：那种写法下隔离只能靠调用方
     * 在 Java 里再比对一次租户与收件人，一旦某处漏比就是越权漏洞。把条件放进查询、
     * 让"查不到"直接成为结果，才是 fail-closed 的写法 —— 忘了传收件人就什么都查不到，
     * 而不是"能查到但不是自己的"。</p>
     */
    Optional<SysNotification> findByNotificationIdAndTenantIdAndRecipientId(
            String notificationId, String tenantId, String recipientId);

    /** 收件箱列表（分页 + 排序由 {@link Pageable} 决定）。 */
    Page<SysNotification> findByTenantIdAndRecipientId(String tenantId, String recipientId, Pageable pageable);

    /** 收件箱列表（按已读状态过滤）。 */
    Page<SysNotification> findByTenantIdAndRecipientIdAndReadAtIsNull(
            String tenantId, String recipientId, Pageable pageable);

    /** 未读数（顶栏角标用，只查 count、不拉列表）。 */
    long countByTenantIdAndRecipientIdAndReadAtIsNull(String tenantId, String recipientId);

    /**
     * 去重窗口用：查某收件人在时间点之后是否已收到过「同类型 + 同标题」的通知。
     *
     * <p>存在的原因见 {@code NotificationService} 的说明 —— 配额超限这类事件
     * 会在每次调用时重复触发，没有去重会瞬间灌满收件箱。</p>
     */
    List<SysNotification> findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
            String tenantId, String recipientId, NotificationType type, String title, LocalDateTime after);

    /** 某收件人的全部未读（批量标记已读用）。 */
    List<SysNotification> findByTenantIdAndRecipientIdAndReadAtIsNull(String tenantId, String recipientId);

    /** 保留期清理（调用方需在事务内执行）。 */
    long deleteByCreatedAtBefore(LocalDateTime before);
}
