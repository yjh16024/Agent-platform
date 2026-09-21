package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.security.rbac.RbacAdminDtos.AssignRolesRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.CreateUserRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.PermissionGroupView;
import com.agentplatform.core.security.rbac.RbacAdminDtos.ResetPasswordRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.RoleView;
import com.agentplatform.core.security.rbac.RbacAdminDtos.SaveRoleRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.UpdateUserRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.UserView;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户/角色管理服务测试。
 *
 * <p>重点覆盖**失败路径**：三处"防自锁"校验都是"只在特定操作顺序下才触发"的分支，
 * 靠手工点界面很难复现，但这些恰恰是会把系统弄成无人可管的场景。</p>
 */
class RbacAdminServiceTest {

    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysPermissionRepository permissionRepository = mock(SysPermissionRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);
    private final SysRolePermissionRepository rolePermissionRepository = mock(SysRolePermissionRepository.class);
    /** 用真实 BCrypt：密码长度校验与哈希是本类职责，mock 掉就没意义了。 */
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final RbacService rbacService = mock(RbacService.class);

    private final RbacAdminService service = new RbacAdminService(
            userRepository, roleRepository, permissionRepository,
            userRoleRepository, rolePermissionRepository, passwordHasher, rbacService);

    private static final String TENANT = "default";
    private static final String ADMIN_ROLE_ID = "role_admin";
    private static final String ADMIN_USER_ID = "user_admin";

    /**
     * 统一兜底"空集合"。
     *
     * <p>Mockito 对**未 stub 的集合返回值是 {@code null} 而不是空集合**，代码顺着 null 走就是 NPE ——
     * 这类失败信息（"NullPointerException at ...stream()"）读起来完全看不出是缺 stub。
     * 这里给所有列表查询铺一层空集合，各用例只 stub 自己关心的那几条。</p>
     *
     * <p>用 {@code lenient()} 是因为很多用例确实用不到全部这些 stub；不加会因为
     * "unnecessary stubbing" 报错。</p>
     */
    @BeforeEach
    void stubCollectionDefaults() {
        lenient().when(userRoleRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(userRoleRepository.findByRoleId(anyString())).thenReturn(List.of());
        lenient().when(userRoleRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(roleRepository.findByRoleIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(roleRepository.findByTenantIdAndRoleCodeIn(anyString(), anyCollection()))
                .thenReturn(List.of());
        lenient().when(permissionRepository.findByPermIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(permissionRepository.findByPermCodeIn(anyCollection())).thenReturn(List.of());
        lenient().when(rolePermissionRepository.findByRoleId(anyString())).thenReturn(List.of());
        lenient().when(userRepository.save(any(SysUser.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(roleRepository.save(any(SysRole.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ 夹具

    private SysUser user(String userId, String username, SysUserStatus status) {
        return SysUser.builder()
                .userId(userId)
                .tenantId(TENANT)
                .username(username)
                .passwordHash("x")
                .status(status)
                .build();
    }

    private SysRole role(String roleId, String code, boolean builtin) {
        return SysRole.builder()
                .roleId(roleId)
                .tenantId(TENANT)
                .roleCode(code)
                .roleName(code)
                .builtin(builtin)
                .build();
    }

    /** 让 userId 这个用户持有 admin 角色，并声明当前租户共有 {@code activeAdmins} 个启用管理员。 */
    private void givenIsAdminWithActiveAdmins(String userId, int activeAdmins) {
        when(roleRepository.findByTenantIdAndRoleCode(TENANT, RbacSeeder.ROLE_ADMIN))
                .thenReturn(Optional.of(role(ADMIN_ROLE_ID, RbacSeeder.ROLE_ADMIN, true)));
        when(userRoleRepository.findByUserId(userId))
                .thenReturn(List.of(SysUserRole.builder().userId(userId).roleId(ADMIN_ROLE_ID).build()));
        List<SysUserRole> links = new java.util.ArrayList<>();
        for (int i = 0; i < activeAdmins; i++) {
            links.add(SysUserRole.builder().userId("user_a" + i).roleId(ADMIN_ROLE_ID).build());
        }
        when(userRoleRepository.findByRoleId(ADMIN_ROLE_ID)).thenReturn(links);
        // (Object) 这个强转不能省：getArgument 是泛型方法，若让编译器自行推断，
        // String.valueOf(...) 会匹配到 valueOf(char[]) 重载，运行时抛 ClassCastException。
        when(userRepository.findByUserId(anyString()))
                .thenAnswer(inv -> Optional.of(user(String.valueOf((Object) inv.getArgument(0)), "u", SysUserStatus.active)));
    }

    private void givenTenantHasNoAdminRole() {
        when(roleRepository.findByTenantIdAndRoleCode(anyString(), anyString())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ 防自锁：停用

    @Test
    void cannotDisableLastActiveAdmin() {
        SysUser admin = user(ADMIN_USER_ID, "admin", SysUserStatus.active);
        when(userRepository.findByUserId(ADMIN_USER_ID)).thenReturn(Optional.of(admin));
        givenIsAdminWithActiveAdmins(ADMIN_USER_ID, 1);

        BizException e = assertThrows(BizException.class,
                () -> service.updateUser(TENANT, ADMIN_USER_ID, new UpdateUserRequest(null, null, "disabled")));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        assertTrue(e.getMessage().contains("最后一个"), "提示要说清为什么被拒");
        verify(userRepository, never()).save(any());
    }

    @Test
    void canDisableAdminWhenOthersRemain() {
        SysUser admin = user(ADMIN_USER_ID, "admin", SysUserStatus.active);
        givenIsAdminWithActiveAdmins(ADMIN_USER_ID, 3);
        // 这两条必须放在 helper **之后**：helper 里的 findByUserId(anyString()) 兜底 stub 会覆盖先设的。
        // 更不能把 findByUserId 设成空列表 —— 那会让"该用户是不是 admin"判定为 false，
        // 校验被提前 return，测试就成了"假通过"（看似放行，其实根本没走到那个分支）。
        when(userRepository.findByUserId(ADMIN_USER_ID)).thenReturn(Optional.of(admin));
        when(userRepository.save(any(SysUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TENANT, ADMIN_USER_ID, new UpdateUserRequest(null, null, "disabled"));

        assertEquals(SysUserStatus.disabled, admin.getStatus());
    }

    /** 非管理员被停用不该受这条规则影响。 */
    @Test
    void disablingNonAdminIsUnaffected() {
        SysUser u = user("user_x", "bob", SysUserStatus.active);
        when(userRepository.findByUserId("user_x")).thenReturn(Optional.of(u));
        when(roleRepository.findByTenantIdAndRoleCode(TENANT, RbacSeeder.ROLE_ADMIN))
                .thenReturn(Optional.of(role(ADMIN_ROLE_ID, RbacSeeder.ROLE_ADMIN, true)));
        when(userRoleRepository.findByUserId("user_x")).thenReturn(List.of());
        when(userRepository.save(any(SysUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TENANT, "user_x", new UpdateUserRequest(null, null, "disabled"));

        assertEquals(SysUserStatus.disabled, u.getStatus());
    }

    // ------------------------------------------------------------------ 防自锁：摘角色 / 删除

    @Test
    void cannotRemoveAdminRoleFromLastAdmin() {
        when(userRepository.findByUserId(ADMIN_USER_ID))
                .thenReturn(Optional.of(user(ADMIN_USER_ID, "admin", SysUserStatus.active)));
        givenIsAdminWithActiveAdmins(ADMIN_USER_ID, 1);

        BizException e = assertThrows(BizException.class,
                () -> service.assignRoles(TENANT, ADMIN_USER_ID, new AssignRolesRequest(Set.of("viewer"))));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        verify(userRoleRepository, never()).deleteByUserId(anyString());
    }

    /** 仍然保留 admin 角色时不应触发守卫（例如顺带加一个 viewer）。 */
    @Test
    void keepingAdminRoleIsAllowed() {
        when(userRepository.findByUserId(ADMIN_USER_ID))
                .thenReturn(Optional.of(user(ADMIN_USER_ID, "admin", SysUserStatus.active)));
        when(roleRepository.findByTenantIdAndRoleCodeIn(TENANT, Set.of("admin", "viewer")))
                .thenReturn(List.of(role(ADMIN_ROLE_ID, "admin", true), role("role_v", "viewer", true)));
        when(userRoleRepository.findByUserId(ADMIN_USER_ID)).thenReturn(List.of());

        service.assignRoles(TENANT, ADMIN_USER_ID, new AssignRolesRequest(Set.of("admin", "viewer")));

        verify(userRoleRepository).deleteByUserId(ADMIN_USER_ID);
        verify(userRoleRepository, times(2)).save(any(SysUserRole.class));
    }

    @Test
    void cannotDeleteSelf() {
        when(userRepository.findByUserId(ADMIN_USER_ID))
                .thenReturn(Optional.of(user(ADMIN_USER_ID, "admin", SysUserStatus.active)));

        BizException e = assertThrows(BizException.class,
                () -> service.deleteUser(TENANT, ADMIN_USER_ID, ADMIN_USER_ID));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        assertTrue(e.getMessage().contains("当前登录"));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void cannotDeleteLastAdmin() {
        when(userRepository.findByUserId(ADMIN_USER_ID))
                .thenReturn(Optional.of(user(ADMIN_USER_ID, "admin", SysUserStatus.active)));
        givenIsAdminWithActiveAdmins(ADMIN_USER_ID, 1);

        assertThrows(BizException.class,
                () -> service.deleteUser(TENANT, ADMIN_USER_ID, "user_other"));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void deleteNormalUserRemovesRoleLinks() {
        SysUser u = user("user_x", "bob", SysUserStatus.active);
        when(userRepository.findByUserId("user_x")).thenReturn(Optional.of(u));
        givenTenantHasNoAdminRole();

        service.deleteUser(TENANT, "user_x", "user_other");

        verify(userRoleRepository).deleteByUserId("user_x");
        verify(userRepository).delete(u);
    }

    // ------------------------------------------------------------------ 用户：创建 / 跨租户

    @Test
    void createUserRejectsDuplicateUsername() {
        when(userRepository.existsByTenantIdAndUsername(TENANT, "bob")).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> service.createUser(TENANT, new CreateUserRequest("bob", "secret123", null, null, Set.of())));

        assertEquals("CONFLICT", e.getErrorCode());
    }

    @Test
    void createUserRejectsShortPassword() {
        BizException e = assertThrows(BizException.class,
                () -> service.createUser(TENANT, new CreateUserRequest("bob", "123", null, null, Set.of())));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    /** 跨租户访问统一按"找不到"处理，不暴露"存在但属于别人"。 */
    @Test
    void crossTenantAccessLooksLikeNotFound() {
        SysUser other = SysUser.builder()
                .userId("user_x").tenantId("other-tenant").username("bob")
                .passwordHash("x").status(SysUserStatus.active).build();
        when(userRepository.findByUserId("user_x")).thenReturn(Optional.of(other));

        BizException e = assertThrows(BizException.class,
                () -> service.updateUser(TENANT, "user_x", new UpdateUserRequest("newName", null, null)));

        assertEquals("NOT_FOUND", e.getErrorCode());
    }

    @Test
    void createUserAssignsRolesAndReturnsView() {
        when(userRepository.existsByTenantIdAndUsername(TENANT, "bob")).thenReturn(false);
        when(userRepository.save(any(SysUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByTenantIdAndRoleCodeIn(TENANT, Set.of("viewer")))
                .thenReturn(List.of(role("role_v", "viewer", true)));
        when(userRoleRepository.findByUserId(anyString())).thenReturn(List.of());
        when(roleRepository.findByRoleIdIn(anyCollection())).thenReturn(List.of(role("role_v", "viewer", true)));

        UserView view = service.createUser(TENANT,
                new CreateUserRequest("bob", "secret123", "鲍勃", "bob@x.io", Set.of("viewer")));

        assertEquals("bob", view.username());
        assertEquals(SysUserStatus.active.name(), view.status());
        verify(userRoleRepository).save(any(SysUserRole.class));
    }

    @Test
    void createUserRejectsUnknownRoleCode() {
        when(userRepository.existsByTenantIdAndUsername(TENANT, "bob")).thenReturn(false);
        when(userRepository.save(any(SysUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByTenantIdAndRoleCodeIn(TENANT, Set.of("ghost"))).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.createUser(TENANT,
                new CreateUserRequest("bob", "secret123", null, null, Set.of("ghost"))));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void resetPasswordRejectsShortPassword() {
        when(userRepository.findByUserId("user_x"))
                .thenReturn(Optional.of(user("user_x", "bob", SysUserStatus.active)));

        assertThrows(BizException.class,
                () -> service.resetPassword(TENANT, "user_x", new ResetPasswordRequest("123")));
        verify(userRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 角色

    @Test
    void cannotDeleteBuiltinRole() {
        when(roleRepository.findByRoleId(ADMIN_ROLE_ID))
                .thenReturn(Optional.of(role(ADMIN_ROLE_ID, "admin", true)));

        BizException e = assertThrows(BizException.class, () -> service.deleteRole(TENANT, ADMIN_ROLE_ID));

        assertEquals("BAD_REQUEST", e.getErrorCode());
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void cannotDeleteRoleStillInUse() {
        when(roleRepository.findByRoleId("role_c"))
                .thenReturn(Optional.of(role("role_c", "counter", false)));
        when(userRoleRepository.findByRoleId("role_c"))
                .thenReturn(List.of(SysUserRole.builder().userId("u1").roleId("role_c").build()));

        BizException e = assertThrows(BizException.class, () -> service.deleteRole(TENANT, "role_c"));

        assertEquals("CONFLICT", e.getErrorCode());
        verify(roleRepository, never()).delete(any());
    }

    /**
     * 最要紧的一条：改角色权限必须**立刻失效缓存**，
     * 否则表现是"权限改了但没生效，重启后才对" —— 这种问题极难排查。
     */
    @Test
    void updateRolePermissionsEvictsCache() {
        when(roleRepository.findByRoleId("role_c"))
                .thenReturn(Optional.of(role("role_c", "counter", false)));
        when(roleRepository.save(any(SysRole.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionRepository.findByPermCodeIn(Set.of("agent:read")))
                .thenReturn(List.of(SysPermission.builder()
                        .permId("perm_1").permCode("agent:read").permName("查看智能体").permGroup("智能体").build()));
        when(rolePermissionRepository.findByRoleId("role_c")).thenReturn(List.of(
                SysRolePermission.builder().roleId("role_c").permId("perm_1").build()));
        when(permissionRepository.findByPermIdIn(anyCollection())).thenReturn(List.of(
                SysPermission.builder()
                        .permId("perm_1").permCode("agent:read").permName("查看智能体").permGroup("智能体").build()));

        RoleView view = service.updateRole(TENANT, "role_c",
                new SaveRoleRequest(null, "计数员", "只读", Set.of("agent:read")));

        verify(rbacService).evictRole("role_c");
        verify(rolePermissionRepository).deleteByRoleId("role_c");
        assertEquals(Set.of("agent:read"), view.permCodes());
    }

    /** 不传 permCodes 表示"只改名称"，不应清空权限、也不该无谓地失效缓存。 */
    @Test
    void updateRoleWithoutPermCodesKeepsPermissions() {
        when(roleRepository.findByRoleId("role_c"))
                .thenReturn(Optional.of(role("role_c", "counter", false)));
        when(roleRepository.save(any(SysRole.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rolePermissionRepository.findByRoleId("role_c")).thenReturn(List.of());

        service.updateRole(TENANT, "role_c", new SaveRoleRequest(null, "新名字", null, null));

        verify(rolePermissionRepository, never()).deleteByRoleId(anyString());
        verify(rbacService, never()).evictRole(anyString());
    }

    @Test
    void createRoleRejectsDuplicateCode() {
        when(roleRepository.existsByTenantIdAndRoleCode(TENANT, "counter")).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> service.createRole(TENANT,
                new SaveRoleRequest("counter", "计数员", null, Set.of())));

        assertEquals("CONFLICT", e.getErrorCode());
    }

    // ------------------------------------------------------------------ 权限清单

    @Test
    void permissionGroupsAreGroupedAndOrdered() {
        when(permissionRepository.findAllByOrderByPermGroupAscPermCodeAsc()).thenReturn(List.of(
                SysPermission.builder().permId("p1").permCode("agent:read").permName("查看智能体").permGroup("智能体").build(),
                SysPermission.builder().permId("p2").permCode("agent:write").permName("编辑智能体").permGroup("智能体").build(),
                SysPermission.builder().permId("p3").permCode("log:read").permName("查看日志").permGroup("运维").build()));

        List<PermissionGroupView> groups = service.permissionGroups();

        assertEquals(2, groups.size());
        assertEquals("智能体", groups.get(0).group());
        assertEquals(2, groups.get(0).items().size());
        assertEquals("运维", groups.get(1).group());
    }
}
