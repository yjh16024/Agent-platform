package com.agentplatform.core.notification;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内通知接口。
 *
 * <h3>权限与收件人隔离是两件事（务必分清）</h3>
 * <ul>
 *   <li><b>权限码</b>（{@code notice:read} / {@code notice:manage}）只回答"这个账号能不能用通知功能"；
 *       它<b>不</b>回答"能看谁的通知"。</li>
 *   <li><b>收件人隔离</b>靠 {@code X-User-Id}：该头由 {@code JwtAuthFilter} 用 token 里的
 *       userId <b>覆盖</b>写入（{@code HeaderOverrideRequest}），调用方伪造不了。
 *       所有查询/变更都会把它带进仓储方法签名里。</li>
 * </ul>
 * <p>之所以要刻意区分：通知是"私信"，不是"按角色可见的数据"。如果只用权限码控制，
 * 那么任何有 {@code notice:read} 的人都能读到别人的通知 —— 那是个越权漏洞。</p>
 *
 * <p>另一个边界：{@code X-User-Id} 在演示模式（{@code security.enabled=false}）下为空，
 * 此时列表为空、未读数为 0。这是正确表现 —— 没有身份就没有收件箱。</p>
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@RequiresPermission("notice:read")
public class NotificationController {

    private final NotificationService notificationService;

    /** 收件箱分页。{@code unreadOnly=true} 时只看未读。 */
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notificationService.list(tenantId, userId, unreadOnly, page, size));
    }

    /**
     * 未读数 —— 侧栏角标用，前端定时轮询这个接口。
     *
     * <p>刻意与列表分开：角标只需要一个数字，让轮询走 count 而不是拉整页数据。</p>
     */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(tenantId, userId)));
    }

    /** 标记单条已读。 */
    @PostMapping("/{notificationId}/read")
    public ApiResponse<Map<String, Object>> markRead(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String notificationId) {
        boolean ok = notificationService.markRead(tenantId, userId, notificationId);
        return ApiResponse.ok(Map.of("updated", ok), ok ? "read" : "not found");
    }

    /** 全部标记已读。返回实际改动的条数（本来就是已读的不会重复计入）。 */
    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        int changed = notificationService.markAllRead(tenantId, userId);
        return ApiResponse.ok(Map.of("updated", changed), "read all");
    }

    /** 删除单条（按收件人校验归属，删不了别人的）。 */
    @DeleteMapping("/{notificationId}")
    public ApiResponse<Map<String, Object>> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String notificationId) {
        boolean ok = notificationService.delete(tenantId, userId, notificationId);
        return ApiResponse.ok(Map.of("removed", ok), ok ? "deleted" : "not found");
    }

    /**
     * 按保留期清理。
     *
     * <p>方法级注解<b>覆盖</b>类级的 {@code notice:read}（{@code PermissionAspect} 的既定规则），
     * 所以这是 admin 专属操作 —— 清理是不可逆的，与"看通知"必须分开。</p>
     */
    @DeleteMapping("/purge")
    @RequiresPermission("notice:manage")
    public ApiResponse<Map<String, Object>> purge(
            @RequestParam(defaultValue = "30") int retentionDays) {
        int deleted = notificationService.purge(retentionDays);
        return ApiResponse.ok(Map.of("deleted", deleted, "retention_days", retentionDays), "purged");
    }
}
