package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.model.entity.SysPermission;
import com.agentplatform.model.entity.SysRole;
import com.agentplatform.model.entity.SysRolePermission;
import com.agentplatform.model.entity.SysUser;
import com.agentplatform.model.entity.SysUserRole;
import com.agentplatform.model.enums.SysUserStatus;
import com.agentplatform.model.repository.SysPermissionRepository;
import com.agentplatform.model.repository.SysRolePermissionRepository;
import com.agentplatform.model.repository.SysRoleRepository;
import com.agentplatform.model.repository.SysUserRepository;
import com.agentplatform.model.repository.SysUserRoleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC 服务测试：登录校验与角色→权限解析（含缓存行为）。
 */
class RbacServiceTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);
    private final SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);

    /** 用真实 BCrypt 而非 mock —— "哈希能配得上校验"本身就是要验证的行为。 */
    private final PasswordHasher passwordHasher = new PasswordHasher();

    private final RbacService service = new RbacService(
            userRepository, roleRepository, permissionRepository,
            userRoleRepository, rolePermissionRepository, passwordHasher);

    private static final String TENANT = "default";

    private SysUser user(String userId, String username, String rawPassword, SysUserStatus status) {
        return SysUser.builder()
                .userId(userId)
                .tenantId(TENANT)
                .username(username)
                .passwordHash(new PasswordHasher().hash(rawPassword))
                .displayName("测试用户")
                .status(status)
                .build();
    }

    private void givenUserHasRole(String userId, String roleId, String roleCode) {
        when(userRoleRepository.findByUserId(userId))
                .thenReturn(List.of(SysUserRole.builder().userId(userId).roleId(roleId).build()));
        when(roleRepository.findByRoleIdIn(anyCollection()))
                .thenReturn(List.of(SysRole.builder()
                        .roleId(roleId).tenantId(TENANT).roleCode(roleCode).roleName(roleCode).build()));
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void authenticateSucceedsWithCorrectPassword() {
        when(userRepository.findByTenantIdAndUsername(TENANT, "alice"))
                .thenReturn(Optional.of(user("user_1", "alice", "s3cret", SysUserStatus.active)));
        givenUserHasRole("user_1", "role_admin", "admin");

        RbacService.LoginResult r = service.authenticate(TENANT, "alice", "s3cret");

        assertEquals("user_1", r.userId());
        assertEquals("alice", r.username());
        assertEquals(Set.of("admin"), r.roleCodes());
    }

    @Test
    void authenticateRejectsWrongPassword() {
        when(userRepository.findByTenantIdAndUsername(TENANT, "alice"))
                .thenReturn(Optional.of(user("user_1", "alice", "s3cret", SysUserStatus.active)));

        BizException e = assertThrows(BizException.class,
                () -> service.authenticate(TENANT, "alice", "wrong"));

        assertEquals("UNAUTHORIZED", e.getErrorCode());
        verify(userRoleRepository, never()).findByUserId(any());
    }

    /**
     * 「用户不存在」与「密码错误」必须给出**完全相同**的提示，
     * 否则登录接口就成了用户枚举器。
     */
    @Test
    void authenticateDoesNotRevealUserExistence() {
        when(userRepository.findByTenantIdAndUsername(TENANT, "ghost")).thenReturn(Optional.empty());
        when(userRepository.findByTenantIdAndUsername(TENANT, "alice"))
                .thenReturn(Optional.of(user("user_1", "alice", "s3cret", SysUserStatus.active)));

        String missingUserMsg = assertThrows(BizException.class,
                () -> service.authenticate(TENANT, "ghost", "whatever")).getMessage();
        String wrongPwdMsg = assertThrows(BizException.class,
                () -> service.authenticate(TENANT, "alice", "whatever")).getMessage();

        assertEquals(missingUserMsg, wrongPwdMsg, "两种失败的提示必须一致");
    }

    @Test
    void authenticateRejectsDisabledUser() {
        when(userRepository.findByTenantIdAndUsername(TENANT, "bob"))
                .thenReturn(Optional.of(user("user_2", "bob", "s3cret", SysUserStatus.disabled)));

        BizException e = assertThrows(BizException.class,
                () -> service.authenticate(TENANT, "bob", "s3cret"));

        assertEquals("FORBIDDEN", e.getErrorCode());
    }

    // ------------------------------------------------------------------ 权限解析与缓存

    @Test
    void permissionsOfEmptyRolesReturnsEmpty() {
        assertTrue(service.permissionsOf(TENANT, List.of()).isEmpty());
        assertTrue(service.permissionsOf(TENANT, null).isEmpty());
        verify(roleRepository, never()).findByTenantIdAndRoleCodeIn(any(), anyCollection());
    }

    @Test
    void permissionsOfUnionsAcrossRoles() {
        when(roleRepository.findByTenantIdAndRoleCodeIn(TENANT, List.of("admin", "viewer")))
                .thenReturn(List.of(
                        SysRole.builder().roleId("role_a").tenantId(TENANT).roleCode("admin").build(),
                        SysRole.builder().roleId("role_v").tenantId(TENANT).roleCode("viewer").build()));
        when(rolePermissionRepository.findByRoleId("role_a"))
                .thenReturn(List.of(SysRolePermission.builder().roleId("role_a").permId("perm_1").build()));
        when(rolePermissionRepository.findByRoleId("role_v"))
                .thenReturn(List.of(SysRolePermission.builder().roleId("role_v").permId("perm_2").build()));
        when(permissionRepository.findByPermIdIn(anyCollection()))
                .thenReturn(List.of(SysPermission.builder().permId("perm_1").permCode("agent:write").build()))
                .thenReturn(List.of(SysPermission.builder().permId("perm_2").permCode("agent:read").build()));

        Set<String> perms = service.permissionsOf(TENANT, List.of("admin", "viewer"));

        assertEquals(Set.of("agent:write", "agent:read"), perms);
    }

    /**
     * 鉴权在每个请求上发生，缓存命中必须真的省掉查库 ——
     * 这条断言就是在守住那个设计意图。
     */
    @Test
    void permissionsOfRoleIsCachedAcrossCalls() {
        when(rolePermissionRepository.findByRoleId("role_a"))
                .thenReturn(List.of(SysRolePermission.builder().roleId("role_a").permId("perm_1").build()));
        when(permissionRepository.findByPermIdIn(anyCollection()))
                .thenReturn(List.of(SysPermission.builder().permId("perm_1").permCode("agent:write").build()));

        service.permissionsOfRole("role_a");
        service.permissionsOfRole("role_a");
        service.permissionsOfRole("role_a");

        verify(rolePermissionRepository, times(1)).findByRoleId("role_a");
        verify(permissionRepository, times(1)).findByPermIdIn(anyCollection());
        assertEquals(1, service.cachedRoleCount());
    }

    @Test
    void evictRoleForcesReload() {
        when(rolePermissionRepository.findByRoleId("role_a"))
                .thenReturn(List.of(SysRolePermission.builder().roleId("role_a").permId("perm_1").build()));
        when(permissionRepository.findByPermIdIn(anyCollection()))
                .thenReturn(List.of(SysPermission.builder().permId("perm_1").permCode("agent:write").build()));

        service.permissionsOfRole("role_a");
        service.evictRole("role_a");
        service.permissionsOfRole("role_a");

        verify(rolePermissionRepository, times(2)).findByRoleId("role_a");
    }

    @Test
    void evictAllClearsCache() {
        when(rolePermissionRepository.findByRoleId(any())).thenReturn(List.of());
        service.permissionsOfRole("role_a");
        assertFalse(service.cachedRoleCount() == 0);

        service.evictAll();

        assertEquals(0, service.cachedRoleCount());
    }
}
