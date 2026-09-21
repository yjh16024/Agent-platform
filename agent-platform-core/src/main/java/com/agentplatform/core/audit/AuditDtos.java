package com.agentplatform.core.audit;

/**
 * 审计的出入参。
 *
 * <p>record + camelCase，与 {@code RbacAdminDtos} / {@code DictDtos} 一致。</p>
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /** 审计记录视图。 */
    public record AuditLogView(String auditId,
                               String userId,
                               String username,
                               /** 操作**当时**的角色快照（逗号分隔）—— 不是现在的角色。 */
                               String roles,
                               String action,
                               String targetType,
                               String targetId,
                               String method,
                               String uri,
                               Integer httpStatus,
                               boolean success,
                               String errorMsg,
                               String ip,
                               String userAgent,
                               Long durationMs,
                               String detail,
                               String createdAt) {
    }

    /** 清理请求。 */
    public record PurgeRequest(Integer keepDays) {
    }

    /** 清理结果。 */
    public record PurgeResult(int deleted, String before, int keepDays) {
    }
}
