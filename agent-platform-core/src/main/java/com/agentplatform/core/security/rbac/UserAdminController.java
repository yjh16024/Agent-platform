package com.agentplatform.core.security.rbac;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RbacAdminDtos.AssignRolesRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.CreateUserRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.ResetPasswordRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.UpdateUserRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口（统一前缀 {@code /api/v1/system/users}）。
 *
 * <p>整个类要求 {@code user:manage}（类级注解，新增方法自动继承管控）。
 * 租户一律取自 {@code X-Tenant-Id} —— 该头已被 {@code JwtAuthFilter} 用 token 里的值覆盖，
 * 所以客户端伪造不了。</p>
 */
@RestController
@RequestMapping("/api/v1/system/users")
@RequiredArgsConstructor
@RequiresPermission("user:manage")
public class UserAdminController {

    private final RbacAdminService adminService;

    /** 用户列表（含各自角色编码）。 */
    @GetMapping
    public ApiResponse<List<UserView>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(adminService.listUsers(tenantId));
    }

    /** 新建用户（可同时指定角色）。 */
    @PostMapping
    public ApiResponse<UserView> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody CreateUserRequest req) {
        return ApiResponse.ok(adminService.createUser(tenantId, req), "created");
    }

    /** 局部更新：显示名 / 邮箱 / 状态（字段为 null 表示不改）。 */
    @PatchMapping("/{userId}")
    public ApiResponse<UserView> update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String userId,
            @RequestBody UpdateUserRequest req) {
        return ApiResponse.ok(adminService.updateUser(tenantId, userId, req), "updated");
    }

    /** 重新分配角色（覆盖式：传全量角色编码）。 */
    @PostMapping("/{userId}/roles")
    public ApiResponse<UserView> assignRoles(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String userId,
            @RequestBody AssignRolesRequest req) {
        return ApiResponse.ok(adminService.assignRoles(tenantId, userId, req), "roles updated");
    }

    /** 重置密码（管理员操作，不需要旧密码）。 */
    @PostMapping("/{userId}/password")
    public ApiResponse<Void> resetPassword(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String userId,
            @RequestBody ResetPasswordRequest req) {
        adminService.resetPassword(tenantId, userId, req);
        return ApiResponse.ok(null, "password reset");
    }

    /**
     * 删除用户。
     * <p>{@code X-User-Id} 由过滤器按 token 注入，用于挡住"删除自己"。</p>
     */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String operatorUserId,
            @PathVariable String userId) {
        adminService.deleteUser(tenantId, userId, operatorUserId);
        return ApiResponse.ok(null, "deleted");
    }
}
