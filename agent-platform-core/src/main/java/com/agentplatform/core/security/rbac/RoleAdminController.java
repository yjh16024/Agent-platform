package com.agentplatform.core.security.rbac;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RbacAdminDtos.PermissionGroupView;
import com.agentplatform.core.security.rbac.RbacAdminDtos.RoleView;
import com.agentplatform.core.security.rbac.RbacAdminDtos.SaveRoleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限点管理接口。
 *
 * <p>类级前缀是 {@code /api/v1/system}（而用户管理用的是 {@code /api/v1/system/users}）——
 * Spring 允许两个 Controller 共享同一前缀，只要具体路径不撞。这里没有撞。</p>
 *
 * <p>要求 {@code role:manage}。</p>
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@RequiresPermission("role:manage")
public class RoleAdminController {

    private final RbacAdminService adminService;

    /**
     * 权限清单（按分组），供界面渲染权限勾选树。
     *
     * <p>放这个 Controller 下而不是单独开一个：它只被角色编辑界面消费，
     * 权限也天然一致（能改角色的人当然能看权限清单）。</p>
     */
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionGroupView>> permissions() {
        return ApiResponse.ok(adminService.permissionGroups());
    }

    /** 角色列表（含每个角色的权限码，界面据此回显勾选状态）。 */
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> listRoles(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(adminService.listRoles(tenantId));
    }

    /** 新建角色。 */
    @PostMapping("/roles")
    public ApiResponse<RoleView> createRole(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody SaveRoleRequest req) {
        return ApiResponse.ok(adminService.createRole(tenantId, req), "created");
    }

    /**
     * 更新角色（名称 / 描述 / 权限集）。
     * <p>权限集变更后服务端会**立即失效权限缓存**，所以保存完就生效，不必重启。</p>
     */
    @PutMapping("/roles/{roleId}")
    public ApiResponse<RoleView> updateRole(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String roleId,
            @RequestBody SaveRoleRequest req) {
        return ApiResponse.ok(adminService.updateRole(tenantId, roleId, req), "updated");
    }

    /** 删除角色（内置角色、以及仍被用户引用的角色会被拒绝）。 */
    @DeleteMapping("/roles/{roleId}")
    public ApiResponse<Void> deleteRole(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String roleId) {
        adminService.deleteRole(tenantId, roleId);
        return ApiResponse.ok(null, "deleted");
    }
}
