package com.agentplatform.core.audit;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import com.agentplatform.core.audit.AuditDtos.AuditLogView;
import com.agentplatform.core.audit.AuditDtos.PurgeRequest;
import com.agentplatform.core.audit.AuditDtos.PurgeResult;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 操作日志（审计）接口。
 *
 * <p>与 {@code /api/v1/logs}（运行日志）的区别见 V17 迁移的说明：这里是**人的操作**。</p>
 *
 * <p><b>查询全部是 GET</b>，因此天然不在 {@link AuditAspect} 的记录范围内
 * （切面只记非 GET），不会出现"查审计反而产生审计"的自我增殖。</p>
 */
@RestController
@RequestMapping("/api/v1/audits")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /**
     * 分页查询。
     *
     * @param action  动作名模糊匹配（如「删除」）
     * @param userId  操作人
     * @param success 结果筛选
     * @param from    起始时间（ISO 格式，如 {@code 2026-09-21T00:00}）
     * @param to      截止时间
     */
    @GetMapping
    @RequiresPermission("audit:read")
    public ApiResponse<PageResult<AuditLogView>> query(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int p = Math.max(0, page);
        int s = size <= 0 ? 20 : Math.min(size, 200);

        Page<AuditLogView> result = auditService.search(
                RbacContext.tenantId(), action, userId, success,
                parseTime(from), parseTime(to),
                PageRequest.of(p, s));

        // 复用 PageResult.from，与其它分页接口返回形状一致（前端表格直接吃 items/total）
        return ApiResponse.ok(PageResult.from(result, x -> x));
    }

    /**
     * 某个对象的操作历史 —— 「这个智能体被谁改过」。
     *
     * <p>答辩演示用：一条链路把**人、时间、动作、结果**串起来，比翻全量列表直观得多。</p>
     */
    @GetMapping("/{targetType}/{targetId}/timeline")
    @RequiresPermission("audit:read")
    public ApiResponse<List<AuditLogView>> timeline(@PathVariable String targetType,
                                                    @PathVariable String targetId) {
        return ApiResponse.ok(auditService.timelineOf(RbacContext.tenantId(), targetType, targetId));
    }

    @GetMapping("/count")
    @RequiresPermission("audit:read")
    public ApiResponse<Long> count() {
        return ApiResponse.ok(auditService.count(RbacContext.tenantId()));
    }

    /**
     * 清理保留期之前的记录。
     *
     * <p>⚠️ 这是**不可逆**操作，而且能抹掉追责线索，所以要求 {@code audit:manage}（只有 admin 有）。
     * 它本身是 POST，因此**会被审计记录** —— "谁清了审计、清到什么时候"同样要留痕，
     * 这正是审计该有的自洽性。</p>
     */
    @PostMapping("/purge")
    @RequiresPermission("audit:manage")
    public ApiResponse<PurgeResult> purge(@RequestBody(required = false) PurgeRequest req) {
        int keepDays = (req == null || req.keepDays() == null) ? 90 : req.keepDays();
        return ApiResponse.ok(auditService.purge(RbacContext.tenantId(), keepDays));
    }

    private static LocalDateTime parseTime(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.trim());
        } catch (DateTimeParseException e) {
            // 时间格式不对就当没传，而不是让整个查询 500 —— 界面上的时间选择器传空值很常见
            return null;
        }
    }
}
