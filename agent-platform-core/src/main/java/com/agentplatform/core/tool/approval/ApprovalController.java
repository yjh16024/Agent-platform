package com.agentplatform.core.tool.approval;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.entity.ToolApproval;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具审批接口。
 *
 * <p><b>身份来自请求，不来自参数</b>：批准/拒绝一律用 {@link RbacContext#userId()}
 * （读 {@code JwtAuthFilter} 用 token 覆盖写入的 {@code X-User-Id}，请求方伪造不了）。
 * 做成入参的话，A 就能批准 B 提交的文件删除。</p>
 *
 * <p>审批与通知/画像的性质不同：它是**放行一个有副作用的操作**，
 * 所以"只能批自己的"这条隔离在 {@code ApprovalService.requirePending} 里做成了硬校验
 * （不匹配直接 403），而不只是"查不到"。</p>
 */
@RestController
@RequestMapping("/api/v1/tool-approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;

    /** 审批列表（默认全部；{@code status=pending} 只看待办）。 */
    @GetMapping
    @RequiresPermission("approval:read")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(0, page);
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Page<ToolApproval> result = service.list(RbacContext.tenantId(), status, PageRequest.of(p, s));
        // 复用 PageResult.from：与其它分页接口返回形状一致（前端表格直接吃 items/total）
        return ApiResponse.ok(PageResult.from(result, ApprovalService::toView));
    }

    /** 待审批条数（前端角标轮询，只 count 不拉列表）。 */
    @GetMapping("/pending-count")
    @RequiresPermission("approval:read")
    public ApiResponse<Map<String, Object>> pendingCount() {
        return ApiResponse.ok(Map.of("count", service.pendingCount(RbacContext.tenantId())));
    }

    /**
     * 批准并立即执行。
     *
     * <p>执行结果（成功或失败原因）会写回记录，前端列表里能看到 ——
     * 用户点完批准不是"消失了"，而是能确认到底改成了没有。</p>
     */
    @PostMapping("/{approvalId}/approve")
    @RequiresPermission("approval:manage")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String approvalId) {
        ToolApproval row = service.approve(RbacContext.tenantId(), RbacContext.userId(), approvalId);
        return ApiResponse.ok(ApprovalService.toView(row));
    }

    /** 拒绝（不执行）。 */
    @PostMapping("/{approvalId}/reject")
    @RequiresPermission("approval:manage")
    public ApiResponse<Map<String, Object>> reject(@PathVariable String approvalId,
                                                  @RequestBody(required = false) RejectRequest req) {
        ToolApproval row = service.reject(RbacContext.tenantId(), RbacContext.userId(), approvalId,
                req == null ? null : req.reason());
        return ApiResponse.ok(ApprovalService.toView(row));
    }

    /**
     * 回滚：把文件恢复到这次操作**之前**的状态。
     *
     * <p>这是"用户敢让智能体改文件"的前提 —— 没有它，审批放行的每一条都是单向门。
     * 回滚是**按快照还原**，不是"反向执行一次操作"，所以在审批之后又手动改过文件时，
     * 结果依然是确定的（回到快照那一刻）。</p>
     *
     * <p>已回滚的不能重复回滚（幂等保护）；执行本来就没成功的也不需要回滚。</p>
     */
    @PostMapping("/{approvalId}/rollback")
    @RequiresPermission("approval:manage")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable String approvalId) {
        ToolApproval row = service.rollback(RbacContext.tenantId(), RbacContext.userId(), approvalId);
        return ApiResponse.ok(ApprovalService.toView(row));
    }

    /**
     * 把超过保留期仍未处理的申请标记为过期。
     *
     * <p>这是**安全阀**而非垃圾回收：一条申请放了几十天，其对话上下文早已过去，
     * 此时若还有人点批准，执行的是一次与当前状态无关的写操作。
     * 与审计/通知的 purge 同理，它改的是状态而非删记录（审批痕迹要留）。</p>
     */
    @DeleteMapping("/expire-stale")
    @RequiresPermission("approval:manage")
    public ApiResponse<Map<String, Object>> expireStale() {
        return ApiResponse.ok(Map.of("expired", service.expireStale()));
    }

    /** 拒绝理由（可选）。 */
    public record RejectRequest(String reason) {
    }
}
