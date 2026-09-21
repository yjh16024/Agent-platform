package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.security.rbac.RbacAdminDtos.AssignRolesRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.CreateUserRequest;
import com.agentplatform.core.security.rbac.RbacAdminDtos.PermissionGroupView;
import com.agentplatform.core.security.rbac.RbacAdminDtos.PermissionItem;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户与角色管理的写侧逻辑。
 *
 * <h3>为什么单独一个类，而不是塞进 {@link RbacService}</h3>
 * {@code RbacService} 在**每个请求的鉴权热路径**上（查角色→权限、走缓存）。
 * 管理操作（建用户、改角色权限）是低频写操作，混进去会让热路径那个类的职责与依赖都变脏。
 *
 * <h3>三处"防自锁"校验（都是为了让系统不会变得无人能管）</h3>
 * <ol>
 *   <li>不能停用 / 删除 / 摘掉**最后一个启用状态的管理员**的角色；</li>
 *   <li>不能删除自己；</li>
 *   <li>内置角色不能删、被用户引用的角色不能删。</li>
 * </ol>
 * 这类事故只能靠手工改数据库救回来，所以宁可在这里挡住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacAdminService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final PasswordHasher passwordHasher;
    /** 用于角色权限变更后**立即失效缓存** —— 否则会出现"改了权限没生效，直到重启"。 */
    private final RbacService rbacService;

    private static final int MIN_PASSWORD_LENGTH = 6;

    // ------------------------------------------------------------------ 用户

    @Transactional(readOnly = true)
    public List<UserView> listUsers(String tenantId) {
        List<SysUser> users = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<String, Set<String>> rolesByUser = roleCodesByUser(users.stream().map(SysUser::getUserId).toList());
        List<UserView> out = new ArrayList<>(users.size());
        for (SysUser u : users) {
            out.add(new UserView(u.getUserId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                    u.getStatus().name(), rolesByUser.getOrDefault(u.getUserId(), Set.of()),
                    u.getCreatedAt() == null ? null : u.getCreatedAt().toString()));
        }
        return out;
    }

    @Transactional
    public UserView createUser(String tenantId, CreateUserRequest req) {
        if (req == null || req.username() == null || req.username().isBlank()) {
            throw BizException.badRequest("用户名不能为空");
        }
        String username = req.username().trim();
        validatePassword(req.password());
        if (userRepository.existsByTenantIdAndUsername(tenantId, username)) {
            throw BizException.conflict("用户名已存在：" + username);
        }

        SysUser user = userRepository.save(SysUser.builder()
                .userId(IdGenerator.generate("user"))
                .tenantId(tenantId)
                .username(username)
                .passwordHash(passwordHasher.hash(req.password()))
                .displayName(blankToNull(req.displayName()))
                .email(blankToNull(req.email()))
                .status(SysUserStatus.active)
                .build());

        applyRoles(tenantId, user.getUserId(), req.roleCodes());
        log.info("[rbac] 已创建用户 {}（角色 {}）", username, req.roleCodes());
        return view(user, roleCodesOf(user.getUserId()));
    }

    @Transactional
    public UserView updateUser(String tenantId, String userId, UpdateUserRequest req) {
        SysUser user = requireUser(tenantId, userId);
        if (req != null) {
            if (req.displayName() != null) {
                user.setDisplayName(blankToNull(req.displayName()));
            }
            if (req.email() != null) {
                user.setEmail(blankToNull(req.email()));
            }
            if (req.status() != null && !req.status().isBlank()) {
                SysUserStatus next = parseStatus(req.status());
                // 停用一个管理员前先确认不会把管理员都停光
                if (next == SysUserStatus.disabled && user.getStatus() == SysUserStatus.active) {
                    ensureNotLastAdmin(tenantId, userId, "停用");
                }
                user.setStatus(next);
            }
        }
        SysUser saved = userRepository.save(user);
        return view(saved, roleCodesOf(userId));
    }

    @Transactional
    public UserView assignRoles(String tenantId, String userId, AssignRolesRequest req) {
        SysUser user = requireUser(tenantId, userId);
        Set<String> next = req == null ? Set.of() : req.roleCodes();
        // 若本次会摘掉它的 admin 角色，先确认不会让管理员归零
        ensureAdminKeptIfRemoving(tenantId, userId, next);
        userRoleRepository.deleteByUserId(userId);
        applyRoles(tenantId, userId, next);
        return view(user, roleCodesOf(userId));
    }

    @Transactional
    public void resetPassword(String tenantId, String userId, ResetPasswordRequest req) {
        requireUser(tenantId, userId);
        String raw = req == null ? null : req.password();
        validatePassword(raw);
        SysUser user = requireUser(tenantId, userId);
        user.setPasswordHash(passwordHasher.hash(raw));
        userRepository.save(user);
        log.info("[rbac] 已重置用户 {} 的密码", user.getUsername());
    }

    @Transactional
    public void deleteUser(String tenantId, String userId, String operatorUserId) {
        SysUser user = requireUser(tenantId, userId);
        if (userId.equals(operatorUserId)) {
            throw BizException.badRequest("不能删除当前登录的账号");
        }
        ensureNotLastAdmin(tenantId, userId, "删除");
        userRoleRepository.deleteByUserId(userId);
        userRepository.delete(user);
        log.info("[rbac] 已删除用户 {}", user.getUsername());
    }

    // ------------------------------------------------------------------ 角色

    @Transactional(readOnly = true)
    public List<RoleView> listRoles(String tenantId) {
        List<SysRole> roles = roleRepository.findByTenantIdOrderByBuiltinDescRoleCodeAsc(tenantId);
        List<RoleView> out = new ArrayList<>(roles.size());
        for (SysRole r : roles) {
            out.add(view(r));
        }
        return out;
    }

    @Transactional
    public RoleView createRole(String tenantId, SaveRoleRequest req) {
        if (req == null || req.roleCode() == null || req.roleCode().isBlank()) {
            throw BizException.badRequest("角色编码不能为空");
        }
        String code = req.roleCode().trim();
        if (roleRepository.existsByTenantIdAndRoleCode(tenantId, code)) {
            throw BizException.conflict("角色编码已存在：" + code);
        }
        SysRole role = roleRepository.save(SysRole.builder()
                .roleId(IdGenerator.generate("role"))
                .tenantId(tenantId)
                .roleCode(code)
                .roleName(blankToNull(req.roleName()) == null ? code : req.roleName().trim())
                .description(blankToNull(req.description()))
                .builtin(false)
                .build());
        replaceRolePermissions(role.getRoleId(), req.permCodes());
        log.info("[rbac] 已创建角色 {}（{} 项权限）", code, size(req.permCodes()));
        return view(role);
    }

    /**
     * 更新角色（名称 / 描述 / 权限集）。
     * <p><b>刻意不改 {@code roleCode}</b>：它是写进 JWT 的稳定标识，改了会让已签发的 token 失效
     * （表现为"用户突然被当成了不存在的角色"）。</p>
     */
    @Transactional
    public RoleView updateRole(String tenantId, String roleId, SaveRoleRequest req) {
        SysRole role = requireRole(tenantId, roleId);
        if (req != null) {
            if (blankToNull(req.roleName()) != null) {
                role.setRoleName(req.roleName().trim());
            }
            if (req.description() != null) {
                role.setDescription(blankToNull(req.description()));
            }
            roleRepository.save(role);
            if (req.permCodes() != null) {
                replaceRolePermissions(roleId, req.permCodes());
                // 关键：权限集变了必须立刻让缓存失效，否则要等重启才生效
                rbacService.evictRole(roleId);
                log.info("[rbac] 角色 {} 权限已更新为 {} 项，缓存已失效", role.getRoleCode(), req.permCodes().size());
            }
        }
        return view(role);
    }

    @Transactional
    public void deleteRole(String tenantId, String roleId) {
        SysRole role = requireRole(tenantId, roleId);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw BizException.badRequest("内置角色不可删除：" + role.getRoleCode());
        }
        List<SysUserRole> refs = userRoleRepository.findByRoleId(roleId);
        if (!refs.isEmpty()) {
            throw BizException.conflict("该角色仍被 " + refs.size() + " 个用户使用，请先解除后再删除");
        }
        rolePermissionRepository.deleteByRoleId(roleId);
        roleRepository.delete(role);
        rbacService.evictRole(roleId);
        log.info("[rbac] 已删除角色 {}", role.getRoleCode());
    }

    // ------------------------------------------------------------------ 权限点

    /**
     * 权限清单，按分组归拢（界面直接渲染成两层树）。
     * <p>数据源是 {@code sys_permission} 表（由 {@code RbacSeeder} 从 {@link RbacPermission} 枚举同步），
     * 所以这里读表而不是枚举 —— 保证界面看到的与库里真正生效的完全一致。</p>
     */
    @Transactional(readOnly = true)
    public List<PermissionGroupView> permissionGroups() {
        Map<String, List<PermissionItem>> byGroup = new LinkedHashMap<>();
        for (SysPermission p : permissionRepository.findAllByOrderByPermGroupAscPermCodeAsc()) {
            byGroup.computeIfAbsent(p.getPermGroup(), k -> new ArrayList<>())
                    .add(new PermissionItem(p.getPermCode(), p.getPermName()));
        }
        return byGroup.entrySet().stream()
                .map(e -> new PermissionGroupView(e.getKey(), e.getValue()))
                .toList();
    }

    // ------------------------------------------------------------------ 内部工具

    private SysUser requireUser(String tenantId, String userId) {
        SysUser user = userRepository.findByUserId(userId)
                .orElseThrow(() -> BizException.notFound("用户", userId));
        if (!tenantId.equals(user.getTenantId())) {
            // 不暴露"存在但属于别的租户"，统一当作找不到
            throw BizException.notFound("用户", userId);
        }
        return user;
    }

    private SysRole requireRole(String tenantId, String roleId) {
        SysRole role = roleRepository.findByRoleId(roleId)
                .orElseThrow(() -> BizException.notFound("角色", roleId));
        if (!tenantId.equals(role.getTenantId())) {
            throw BizException.notFound("角色", roleId);
        }
        return role;
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("密码不能为空");
        }
        if (raw.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.badRequest("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private SysUserStatus parseStatus(String s) {
        try {
            return SysUserStatus.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("非法的状态值：" + s + "（可选 active / disabled）");
        }
    }

    /** 按角色**编码**分配（前端传的是编码，不是内部 roleId）。 */
    private void applyRoles(String tenantId, String userId, Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        List<SysRole> roles = roleRepository.findByTenantIdAndRoleCodeIn(tenantId, roleCodes);
        if (roles.size() != roleCodes.size()) {
            Set<String> found = roles.stream().map(SysRole::getRoleCode).collect(Collectors.toSet());
            Set<String> missing = new LinkedHashSet<>(roleCodes);
            missing.removeAll(found);
            throw BizException.badRequest("角色不存在：" + missing);
        }
        for (SysRole r : roles) {
            userRoleRepository.save(SysUserRole.builder()
                    .userId(userId)
                    .roleId(r.getRoleId())
                    .build());
        }
    }

    private void replaceRolePermissions(String roleId, Set<String> permCodes) {
        rolePermissionRepository.deleteByRoleId(roleId);
        if (permCodes == null || permCodes.isEmpty()) {
            return;
        }
        List<SysPermission> perms = permissionRepository.findByPermCodeIn(permCodes);
        if (perms.size() != permCodes.size()) {
            Set<String> found = perms.stream().map(SysPermission::getPermCode).collect(Collectors.toSet());
            Set<String> missing = new LinkedHashSet<>(permCodes);
            missing.removeAll(found);
            throw BizException.badRequest("权限不存在：" + missing);
        }
        for (SysPermission p : perms) {
            rolePermissionRepository.save(SysRolePermission.builder()
                    .roleId(roleId)
                    .permId(p.getPermId())
                    .build());
        }
    }

    private Set<String> roleCodesOf(String userId) {
        List<String> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleRepository.findByRoleIdIn(roleIds).stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Set<String>> roleCodesByUser(List<String> userIds) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        List<SysUserRole> links = userRoleRepository.findByUserIdIn(userIds);
        if (links.isEmpty()) {
            return out;
        }
        Map<String, SysRole> rolesById = roleRepository
                .findByRoleIdIn(links.stream().map(SysUserRole::getRoleId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SysRole::getRoleId, r -> r, (a, b) -> a));
        for (SysUserRole link : links) {
            SysRole role = rolesById.get(link.getRoleId());
            if (role != null) {
                out.computeIfAbsent(link.getUserId(), k -> new LinkedHashSet<>()).add(role.getRoleCode());
            }
        }
        return out;
    }

    private UserView view(SysUser u, Set<String> roleCodes) {
        return new UserView(u.getUserId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                u.getStatus().name(), roleCodes,
                u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
    }

    private RoleView view(SysRole r) {
        Set<String> codes = rolePermissionRepository.findByRoleId(r.getRoleId()).stream()
                .map(SysRolePermission::getPermId)
                .collect(Collectors.toSet());
        Set<String> permCodes = codes.isEmpty() ? Set.of()
                : permissionRepository.findByPermIdIn(codes).stream()
                        .map(SysPermission::getPermCode)
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        return new RoleView(r.getRoleId(), r.getRoleCode(), r.getRoleName(), r.getDescription(),
                Boolean.TRUE.equals(r.getBuiltin()), permCodes);
    }

    /** 停用 / 删除 / 摘角色前的"最后一个管理员"守卫。 */
    private void ensureNotLastAdmin(String tenantId, String userId, String action) {
        Optional<SysRole> adminRole = roleRepository.findByTenantIdAndRoleCode(tenantId, RbacSeeder.ROLE_ADMIN);
        if (adminRole.isEmpty()) {
            return;
        }
        String adminRoleId = adminRole.get().getRoleId();
        boolean isAdmin = userRoleRepository.findByUserId(userId).stream()
                .anyMatch(link -> adminRoleId.equals(link.getRoleId()));
        if (!isAdmin) {
            return;
        }
        int activeAdmins = 0;
        for (SysUserRole link : userRoleRepository.findByRoleId(adminRoleId)) {
            Optional<SysUser> u = userRepository.findByUserId(link.getUserId());
            if (u.isPresent() && u.get().getStatus() == SysUserStatus.active) {
                activeAdmins++;
            }
        }
        if (activeAdmins <= 1) {
            throw BizException.badRequest("不能" + action + "：这是最后一个启用状态的管理员，"
                    + "操作后将无人能管理平台。请先给其他用户授予管理员角色。");
        }
    }

    /** 本次分配会摘掉 admin 角色时，检查是否还有别的启用管理员。 */
    private void ensureAdminKeptIfRemoving(String tenantId, String userId, Set<String> nextRoleCodes) {
        if (nextRoleCodes != null && nextRoleCodes.contains(RbacSeeder.ROLE_ADMIN)) {
            return;   // 仍然保留 admin，无需检查
        }
        ensureNotLastAdmin(tenantId, userId, "移除管理员角色");
    }

    private static int size(Set<String> s) {
        return s == null ? 0 : s.size();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
