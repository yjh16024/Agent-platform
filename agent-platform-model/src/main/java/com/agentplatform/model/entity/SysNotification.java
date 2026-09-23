package com.agentplatform.model.entity;

import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内通知（面向人的提醒）。
 *
 * <p>与相邻两张表的分工 —— 这是本实体存在的全部理由（详见 V18 迁移头部注释）：</p>
 * <ul>
 *   <li>{@code LogIndex}（运行日志）：系统在做什么，答"这次调用为什么慢"；</li>
 *   <li>{@code SysAuditLog}（操作审计）：人做了什么，答"这个智能体昨天是谁改的"；</li>
 *   <li><b>本实体</b>：有什么需要当前用户处理，答"我该看什么"。</li>
 * </ul>
 *
 * <p><b>点对点，不做广播</b>：{@code recipientId} 必填。若用"空 = 所有人"表达广播，
 * 一行记录会被多人共享，一个人标已读会让所有人都变已读。真要发公告，
 * 应在服务层给每个收件人各插一行。</p>
 *
 * <p>与既有实体约定保持一致：{@code id} 为自增物理主键，{@code notificationId} 为业务 ID
 * （{@code IdGenerator.generate("notice")}），对外只暴露后者。</p>
 */
@Entity
@Table(name = "sys_notification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "notification_id", nullable = false, length = 64)
    private String notificationId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 收件人用户 ID（点对点，必填）。 */
    @Column(name = "recipient_id", nullable = false, length = 64)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    @Builder.Default
    private NotificationType type = NotificationType.system;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    @Builder.Default
    private NotificationLevel level = NotificationLevel.info;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", length = 1000)
    private String content;

    /** 点击跳转的前端路径（如 {@code /agents}），空则前端不可点。 */
    @Column(name = "link", length = 300)
    private String link;

    /** 已读时间；{@code null} = 未读。用时间戳而不是布尔，顺带留下"什么时候读的"。 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 由 DB 的 {@code DEFAULT CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
