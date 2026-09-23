package com.agentplatform.core.tool;

/**
 * 工具执行上下文。
 *
 * <p>{@code sessionId} 是 2026-09-23 为**工具审批**加的：写类工具被调用时并不直接执行，
 * 而是提交一条待审批申请，用户在前端批准后才由 {@code ApprovalService} 执行。
 * 这条链路需要知道"申请属于哪个会话"，才能在前端把审批与对话关联起来
 * （会话列表上标"有 N 条待批"、点进会话能看到待批项）。</p>
 *
 * <p>保留三参构造器与三参 {@code of(...)}：既有的工具与测试都按三参调用，
 * 加字段不能让它们全部改一遍（{@code null} 的 sessionId 表示"这条链路没有会话上下文"，
 * 比如工作流节点里调工具 —— 那种场景下审批仍可提交，只是不带会话归属）。</p>
 *
 * @param tenantId  租户 ID
 * @param agentId   智能体 ID
 * @param runId     运行 ID
 * @param sessionId 会话 ID（可为空：工作流等无会话的场景）
 */
public record ToolContext(String tenantId, String agentId, String runId, String sessionId) {

    /** 兼容旧调用：无会话上下文。 */
    public ToolContext(String tenantId, String agentId, String runId) {
        this(tenantId, agentId, runId, null);
    }

    public static ToolContext of(String tenantId, String agentId, String runId) {
        return new ToolContext(tenantId, agentId, runId, null);
    }

    public static ToolContext of(String tenantId, String agentId, String runId, String sessionId) {
        return new ToolContext(tenantId, agentId, runId, sessionId);
    }
}
