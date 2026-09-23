package com.agentplatform.model.entity;

import com.agentplatform.model.enums.ToolApprovalStatus;
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
 * 工具审批记录（表 {@code sys_tool_approval}）。
 *
 * <p>把"有副作用的工具调用"从**立即执行**改成**先申请、后放行**。
 * 这是写类工具能上线的前提 —— 详见 V20 迁移头部注释里的完整理由。</p>
 *
 * <h3>核心字段的作用</h3>
 * <ul>
 *   <li>{@code toolArgs}：待执行参数的 JSON 快照。批准时**按它执行**，
 *       而不是"让模型再调一次" —— 否则模型第二次可能换个参数，用户批的与实际执行的不是同一件事；</li>
 *   <li>{@code summary}：给用户看的影响摘要（用户不该读 JSON 去猜这次要改什么）；</li>
 *   <li>{@code decidedBy} / {@code decidedAt}：审批留痕（"谁放行的"必须可查）；</li>
 *   <li>{@code agentId} / {@code sessionId} / {@code runId}：执行时要还原上下文
 *       （工具执行需要 {@code ToolContext}）。</li>
 * </ul>
 */
@Entity
@Table(name = "sys_tool_approval")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 业务 ID（{@code IdGenerator.generate("appr")}），对外只暴露它。 */
    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "run_id", length = 64)
    private String runId;

    /** 触发这次工具调用的用户（审批人须与申请人一致，见 ApprovalService）。 */
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    /** 待执行参数的 JSON 快照（批准时按它执行，避免 TOCTOU）。 */
    @Column(name = "tool_args", columnDefinition = "text")
    private String toolArgs;

    /** 给用户看的影响摘要。 */
    @Column(name = "summary", length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ToolApprovalStatus status = ToolApprovalStatus.pending;

    /** 批准后的执行结果摘要（截断，完整内容在工具返回里）。 */
    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "error_msg", length = 1000)
    private String errorMsg;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /**
     * 改前快照目录（V21）。
     *
     * <p>为空表示本次操作没有产生快照。以"审批"为单位而不是"每文件一份 .bak"：
     * 用户的心智是"撤销刚才那次操作"，而一次批准可能改文件、也可能新建文件 ——
     * 单文件备份表达不了"这次操作整体是什么"。</p>
     */
    @Column(name = "snapshot_dir", length = 500)
    private String snapshotDir;

    /**
     * 回滚时间（V21）；{@code null} = 未回滚。
     *
     * <p>用时间戳而非布尔：既判"是否已回滚"，又留下"什么时候回滚的"。
     * 它同时是**幂等保护**的依据 —— 已回滚的记录不允许再次回滚。</p>
     */
    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    /** 由 DB 的 {@code DEFAULT CURRENT_TIMESTAMP} 负责，JPA 不参与写入。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
