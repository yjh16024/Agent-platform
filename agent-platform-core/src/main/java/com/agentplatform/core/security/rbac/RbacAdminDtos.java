package com.agentplatform.core.security.rbac;

import java.util.List;
import java.util.Set;

/**
 * 用户与角色管理接口的出入参。
 *
 * <p>遵循项目契约：强类型 record 出入参用 camelCase
 * （与 {@code AgentCreateRequest} / {@code SessionDtos} 一致）。</p>
 */
public final class RbacAdminDtos {

    private RbacAdminDtos() {
    }

    // ------------------------------------------------------------------ 用户

    /** 用户视图。**不含 passwordHash** —— 任何往外传的结构都不该带上它。 */
    public record UserView(String userId,
                           String username,
                           String displayName,
                           String email,
                           String status,
                           Set<String> roleCodes,
                           String createdAt) {
    }

    public record CreateUserRequest(String username,
                                    String password,
                                    String displayName,
                                    String email,
                                    Set<String> roleCodes) {
    }

    /** 局部更新：字段为 null 表示不改那一项。 */
    public record UpdateUserRequest(String displayName, String email, String status) {
    }

    public record AssignRolesRequest(Set<String> roleCodes) {
    }

    public record ResetPasswordRequest(String password) {
    }

    // ------------------------------------------------------------------ 角色

    /** 角色视图（含权限码，供界面直接渲染勾选状态）。 */
    public record RoleView(String roleId,
                           String roleCode,
                           String roleName,
                           String description,
                           boolean builtin,
                           Set<String> permCodes) {
    }

    /** 新建/更新角色。更新时 {@code roleCode} 被忽略（它是写进 token 的稳定标识，改了会让已签发 token 失效）。 */
    public record SaveRoleRequest(String roleCode,
                                  String roleName,
                                  String description,
                                  Set<String> permCodes) {
    }

    // ------------------------------------------------------------------ 权限点

    public record PermissionItem(String code, String name) {
    }

    /** 按分组归拢的权限清单（界面上就是一棵两层树）。 */
    public record PermissionGroupView(String group, List<PermissionItem> items) {
    }
}
