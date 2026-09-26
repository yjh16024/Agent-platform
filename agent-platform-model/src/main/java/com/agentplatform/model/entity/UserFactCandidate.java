package com.agentplatform.model.entity;

import com.agentplatform.model.enums.UserFactCategory;
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
 * 长期记忆：**待用户确认**的自动抽取画像（表 {@code sys_user_fact_candidate}）。
 *
 * <p>与 {@link UserFact} 的关系：本表是"系统猜的"，那张表是"用户认过的"。
 * 用户在界面上点「采纳」才会把一条候选搬进 {@code sys_user_fact}。</p>
 *
 * <h3>为什么单独一张表（而不是给 UserFact 加 status 列）</h3>
 * {@code UserFact} 的内容会被 {@code UserFactService.render()} **直接注入系统提示词**。
 * 若把未确认的推测混在同一张表里，就必须在 render 的查询路径上永远记得加一个过滤条件 ——
 * 一旦哪次漏加，**未经用户确认的推测就会被当成事实喂给模型**，而这是本功能最不能出的错。
 * 分表后 render 读的那张表天然只含已确认项，**结构上不可能出错**。
 *
 * <h3>三个字段值得说明</h3>
 * <ul>
 *   <li>{@code rejectedAt} —— 用户点「忽略」时打时间戳而**不删除记录**。
 *       否则下一轮对话又会抽出同样的东西重新打扰，那是最招人烦的体验。
 *       抽取侧会跳过已拒绝的键。</li>
 *   <li>{@code sourceSessionId} / {@code extractedBy} —— 可追溯：用户质疑
 *       "你凭什么这么记我"时要答得出来，排查"为什么抽出这条"时也不必猜。</li>
 * </ul>
 *
 * <p>同一用户同一 {@code factKey} 只保留一条（DB 唯一约束兜底），
 * 所以重复抽取是 upsert 覆盖而不是堆积。</p>
 */
@Entity
@Table(name = "sys_user_fact_candidate")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFactCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 业务 ID（{@code IdGenerator.generate("fctc")}），对外只暴露它。 */
    @Column(name = "candidate_id", nullable = false, length = 64)
    private String candidateId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 归属用户（业务 ID，对应 {@code sys_user.user_id}）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "fact_key", nullable = false, length = 100)
    private String factKey;

    @Column(name = "fact_value", nullable = false, length = 1000)
    private String factValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    @Builder.Default
    private UserFactCategory category = UserFactCategory.other;

    /** 抽取自哪个会话（可追溯）。 */
    @Column(name = "source_session_id", length = 64)
    private String sourceSessionId;

    /** 由哪个模型抽取的（可追溯）。 */
    @Column(name = "extracted_by", length = 120)
    private String extractedBy;

    /** 用户点「忽略」的时间；{@code null} 表示仍在待确认列表里。 */
    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** 由 DB 的 {@code DEFAULT CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 由 DB 的 {@code ON UPDATE CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /** 是否仍在待确认状态。 */
    public boolean isPending() {
        return rejectedAt == null;
    }
}
