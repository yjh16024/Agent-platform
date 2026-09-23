package com.agentplatform.model.entity;

import com.agentplatform.model.enums.UserFactCategory;
import com.agentplatform.model.enums.UserFactSource;
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
 * 长期记忆：用户显式画像的一条事实（表 {@code sys_user_fact}）。
 *
 * <p>多层级记忆的第三层，另外三层见 {@code SessionRecentCache}（短期）、
 * {@link Session#getSummary()}（中期）、向量记忆（第四层）。</p>
 *
 * <h3>为什么是"一条条事实"而不是"一段文本"</h3>
 * 最省事的做法是给每个用户存一段自由文本画像。但那样做不到「按条编辑 / 按条删除」，
 * 而用户对画像的控制权恰恰是这类功能的信任基础（他要能看见系统记住了什么、
 * 并且随时改掉其中某一条）。键值对让 UI 能做成列表、注入时也能挑着用。
 * 代价只是多两列，见 V19 迁移头部注释。
 *
 * <h3>隐私约定</h3>
 * 本表内容会进入系统提示词，因此所有查询**必须**带 {@code tenantId + userId}
 * （仓储方法签名强制，不提供"只按 factId 查"的方法）；并支持用户一键清除。
 */
@Entity
@Table(name = "sys_user_fact")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 业务 ID（{@code IdGenerator.generate("fact")}），对外只暴露它。 */
    @Column(name = "fact_id", nullable = false, length = 64)
    private String factId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 归属用户（业务 ID，对应 {@code sys_user.user_id}）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 事实的键，如「职业」「常用语言」；同一用户内唯一（DB 有唯一约束兜底）。 */
    @Column(name = "fact_key", nullable = false, length = 100)
    private String factKey;

    @Column(name = "fact_value", nullable = false, length = 1000)
    private String factValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    @Builder.Default
    private UserFactCategory category = UserFactCategory.other;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private UserFactSource source = UserFactSource.manual;

    /** 由 DB 的 {@code DEFAULT CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 由 DB 的 {@code ON UPDATE CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
