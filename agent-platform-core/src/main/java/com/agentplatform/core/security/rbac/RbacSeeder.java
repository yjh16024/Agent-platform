package com.agentplatform.core.security.rbac;

import com.agentplatform.common.util.IdGenerator;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 种子数据：权限点同步 → 内置角色 → 初始管理员。
 *
 * <h3>为什么用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</h3>
 * 两条硬理由（第一条抄自 {@code BuiltinPluginRegistrar} 2026-09-20 的踩坑记录）：
 * <ol>
 *   <li>桌面版以 {@code -Dspring.main.lazy-initialization=true} 启动，而本类**不被任何 bean 依赖**，
 *       懒加载下永远不会被创建，{@code @PostConstruct} 也就永不执行 ——
 *       表现为「表建好了但一条种子数据都没有，也没有任何日志」，极难排查；</li>
 *   <li>本类要往 V15 新建的表里写数据，必须确保 Flyway 已迁移完毕。
 *       {@code ApplicationReadyEvent} 在容器完全就绪后触发，天然满足这个顺序。</li>
 * </ol>
 *
 * <h3>幂等性与"补齐"策略</h3>
 * <ul>
 *   <li>权限点：按 {@code permCode} 比对，缺的补、多余的删；</li>
 *   <li>内置角色：已存在时**只补齐、不重置** —— 补上"按角色定义应该有、但库里缺失"的权限，
 *       **不删除**多余项（用户可能在界面上给内置角色加过权限，删了等于每次启动都吃掉配置）。
 *       <br>为什么"缺失必须补"：{@code RbacPermission} 枚举新增权限点时（例如后来加入的
 *       {@code agent:invoke} / {@code session:read} / {@code model:manage}），老库里的 admin
 *       不会自动获得它们 —— 结果会是**管理员被自己的系统拒绝**，而且只在访问新加管控的接口时
 *       才暴露，很难第一时间联想到"权限点集合变了"这件事；</li>
 *   <li>初始管理员：仅当该租户下"一个用户都没有"时创建；
 *       已存在时若设置了 {@code RBAC_ADMIN_RESET_PASSWORD}，则用它重置密码。</li>
 * </ul>
 */
@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class RbacSeeder {

    /** 内置角色编码 —— 也是 token 里 roles 的取值，改动会让已签发的 token 失效。 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_VIEWER = "viewer";

    private final SysPermissionRepository permissionRepository;
    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordHasher passwordHasher;

    @Value("${agent-platform.security.rbac.enabled:false}")
    private boolean rbacEnabled;

    @Value("${agent-platform.tenant.default-id:default}")
    private String defaultTenantId;

    /** 初始管理员密码；留空则随机生成并打到日志里。 */
    @Value("${RBAC_ADMIN_PASSWORD:}")
    private String adminPasswordOverride;

    /**
     * 已存在 admin 时的**强制重置密码**（忘记密码 / 部署时指定密码用）。
     * <p>⚠️ 用完必须移除该环境变量 —— 只要它还设着，每次启动都会把密码改成这个值。</p>
     */
    @Value("${RBAC_ADMIN_RESET_PASSWORD:}")
    private String adminResetPassword;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (!rbacEnabled) {
            log.debug("[rbac] 未启用（agent-platform.security.rbac.enabled=false），跳过种子数据");
            return;
        }
        int perms = syncPermissions();
        int roles = seedBuiltinRoles();
        boolean adminCreated = seedInitialAdmin();
        boolean pwdReset = resetAdminPasswordIfRequested();
        log.info("[rbac] 种子数据就绪：权限点同步 {} 条，新建内置角色 {} 个，初始管理员{}{}",
                perms, roles, adminCreated ? "已创建" : "已存在（跳过）",
                pwdReset ? "；admin 密码已按 RBAC_ADMIN_RESET_PASSWORD 重置" : "");
    }

    // ------------------------------------------------------------------ 1. 权限点

    /**
     * 把 {@link RbacPermission} 枚举同步进 {@code sys_permission}。
     *
     * @return 新插入的条数
     */
    private int syncPermissions() {
        Set<String> wanted = RbacPermission.allCodes();
        List<SysPermission> existing = permissionRepository.findAll();
        Set<String> existingCodes = new HashSet<>();
        for (SysPermission p : existing) {
            existingCodes.add(p.getPermCode());
        }

        int inserted = 0;
        for (RbacPermission def : RbacPermission.values()) {
            if (existingCodes.contains(def.code())) {
                continue;
            }
            permissionRepository.save(SysPermission.builder()
                    .permId(IdGenerator.generate("perm"))
                    .permCode(def.code())
                    .permName(def.label())
                    .permGroup(def.group())
                    .build());
            inserted++;
        }

        // 枚举里已删除的权限点：先解除角色引用再删，否则会留下悬空的 role_id → perm_id
        for (SysPermission p : existing) {
            if (!wanted.contains(p.getPermCode())) {
                rolePermissionRepository.deleteByPermId(p.getPermId());
                permissionRepository.delete(p);
                log.info("[rbac] 权限点 {} 已不在枚举中，连同角色引用一并移除", p.getPermCode());
            }
        }
        return inserted;
    }

    // ------------------------------------------------------------------ 2. 内置角色

    /** 内置角色定义（创建与补齐共用同一份，避免两处漂移）。 */
    private record BuiltinRole(String code, String name, String description, Set<String> permCodes) {
    }

    private List<BuiltinRole> builtinRoles() {
        // admin 拿全部；operator 拿业务权限但不含「系统管理」；viewer 只拿 :read
        Set<String> all = RbacPermission.allCodes();
        Set<String> operator = new LinkedHashSet<>(all);
        operator.removeAll(RbacPermission.systemCodes());
        return List.of(
                new BuiltinRole(ROLE_ADMIN, "管理员", "拥有全部权限", all),
                new BuiltinRole(ROLE_OPERATOR, "操作员", "业务功能读写，不含用户与角色管理", operator),
                new BuiltinRole(ROLE_VIEWER, "只读", "仅可查看，不能修改任何配置", RbacPermission.readOnlyCodes()));
    }

    /** @return 新建的角色个数（已存在的走"补齐"路径，不计入） */
    private int seedBuiltinRoles() {
        int created = 0;
        for (BuiltinRole def : builtinRoles()) {
            Optional<SysRole> existing = roleRepository.findByTenantIdAndRoleCode(defaultTenantId, def.code());
            if (existing.isPresent()) {
                topUpRole(existing.get(), def);
            } else {
                createRole(def);
                created++;
            }
        }
        return created;
    }

    private void createRole(BuiltinRole def) {
        SysRole role = roleRepository.save(SysRole.builder()
                .roleId(IdGenerator.generate("role"))
                .tenantId(defaultTenantId)
                .roleCode(def.code())
                .roleName(def.name())
                .description(def.description())
                .builtin(true)
                .build());

        // 权限码 → 权限 ID（关联表按 perm_id 存）
        List<SysPermission> perms = permissionRepository.findByPermCodeIn(def.permCodes());
        for (SysPermission p : perms) {
            rolePermissionRepository.save(SysRolePermission.builder()
                    .roleId(role.getRoleId())
                    .permId(p.getPermId())
                    .build());
        }
        log.info("[rbac] 内置角色已创建：{}（{} 项权限）", def.code(), perms.size());
    }

    /**
     * 为已存在的内置角色**补齐**缺失权限，**不删除**任何多余项。
     * <p>只补不删的理由见类注释：删除会吃掉用户在界面上做过的调整。</p>
     */
    private void topUpRole(SysRole role, BuiltinRole def) {
        List<SysPermission> want = permissionRepository.findByPermCodeIn(def.permCodes());
        if (want.isEmpty()) {
            return;
        }
        Set<String> havePermIds = rolePermissionRepository.findByRoleId(role.getRoleId()).stream()
                .map(SysRolePermission::getPermId)
                .collect(Collectors.toSet());
        int added = 0;
        for (SysPermission p : want) {
            if (!havePermIds.contains(p.getPermId())) {
                rolePermissionRepository.save(SysRolePermission.builder()
                        .roleId(role.getRoleId())
                        .permId(p.getPermId())
                        .build());
                added++;
            }
        }
        if (added > 0) {
            log.info("[rbac] 内置角色 {} 补齐 {} 项新权限（原有 {} 项保留）",
                    role.getRoleCode(), added, havePermIds.size());
        }
    }

    // ------------------------------------------------------------------ 3. 初始管理员

    /**
     * 租户下一个用户都没有时，创建一个 admin 账号并打印初始密码。
     *
     * <p>密码来源：环境变量 {@code RBAC_ADMIN_PASSWORD} 优先，未设置则随机生成 12 位。
     * 刻意**不写默认弱口令**（如 admin/123456）—— 那类口令一旦被忘记修改，
     * 等于给平台留了一个公开后门；随机生成 + 只在首次启动的日志里出现一次更安全。</p>
     *
     * @return 是否新建
     */
    private boolean seedInitialAdmin() {
        long existingUsers = userRepository.countByTenantIdAndStatus(defaultTenantId, SysUserStatus.active)
                + userRepository.countByTenantIdAndStatus(defaultTenantId, SysUserStatus.disabled);
        if (existingUsers > 0) {
            return false;
        }

        boolean fromEnv = adminPasswordOverride != null && !adminPasswordOverride.isBlank();
        String rawPassword = fromEnv ? adminPasswordOverride.trim() : IdGenerator.random(12);

        SysUser admin = userRepository.save(SysUser.builder()
                .userId(IdGenerator.generate("user"))
                .tenantId(defaultTenantId)
                .username("admin")
                .passwordHash(passwordHasher.hash(rawPassword))
                .displayName("平台管理员")
                .status(SysUserStatus.active)
                .build());

        SysRole adminRole = roleRepository.findByTenantIdAndRoleCode(defaultTenantId, ROLE_ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "内置角色 admin 缺失，无法创建初始管理员（Seeder 内部顺序错误）"));
        userRoleRepository.save(SysUserRole.builder()
                .userId(admin.getUserId())
                .roleId(adminRole.getRoleId())
                .build());

        if (fromEnv) {
            log.warn("[rbac] 已创建初始管理员：username=admin，密码取自 RBAC_ADMIN_PASSWORD。请登录后尽快修改。");
        } else {
            log.warn("""
                    [rbac] 已创建初始管理员：username=admin，随机初始密码 = {}
                    该密码只在此处打印一次，请立即登录并修改；
                    也可在下次全新初始化时用环境变量 RBAC_ADMIN_PASSWORD 指定密码。""", rawPassword);
        }
        return true;
    }

    /**
     * 若设置了 {@code RBAC_ADMIN_RESET_PASSWORD} 且 admin 已存在，则重置其密码。
     *
     * <p>用途：管理员忘记密码、或部署时想指定初始密码（此时用户早已存在，
     * {@code RBAC_ADMIN_PASSWORD} 不再生效）。</p>
     *
     * <p>⚠️ 只要该环境变量还设着，**每次启动都会重置一次** —— 所以用完立刻移除，
     * 否则用户在界面上改的密码会在下次重启时被覆盖回去。这条警告在日志里也打了一份。</p>
     *
     * @return 是否执行了重置
     */
    private boolean resetAdminPasswordIfRequested() {
        if (adminResetPassword == null || adminResetPassword.isBlank()) {
            return false;
        }
        Optional<SysUser> found = userRepository.findByTenantIdAndUsername(defaultTenantId, "admin");
        if (found.isEmpty()) {
            return false;
        }
        SysUser admin = found.get();
        admin.setPasswordHash(passwordHasher.hash(adminResetPassword.trim()));
        userRepository.save(admin);
        log.warn("""
                [rbac] 已按 RBAC_ADMIN_RESET_PASSWORD 重置 admin 的密码。
                ⚠️ 请立刻移除该环境变量，否则每次启动都会把它重置回同一个值。""");
        return true;
    }
}
