package com.agentplatform.core.security.rbac;

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
import com.agentplatform.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RBAC 核心服务：登录校验 + 角色/权限解析。
 *
 * <h3>缓存策略（这里是有意设计，别当优化删掉）</h3>
 * 鉴权发生在**每个请求**上，如果每次都查库，等于给所有接口加一次
 * {@code sys_user_role + sys_role + sys_role_permission + sys_permission} 四表查询。
 * 所以按 **roleId → 权限码集合** 做进程内缓存：
 * <ul>
 *   <li>粒度选 roleId：角色数量少（个位数）、且权限变更总是发生在角色上，失效精确；</li>
 *   <li>不用 Redis：这是"进程内、可重建"的派生数据，丢了最多多查一次库；
 *       而 Redis 在多实例部署下还需要额外的一致性处理，不划算（桌面单机更是纯负担）；</li>
 *   <li>**不做 TTL**：TTL 只会造成"过期后第一批请求变慢"，而正确性由显式失效保证
 *       （改角色权限的接口调用 {@link #evictRole}）。</li>
 * </ul>
 *
 * <h3>为什么 token 里不放权限点</h3>
 * 见 {@code JwtUtil.roles} 的说明：放角色码才能让权限调整即时生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacService {

    /** 请求属性键：本次请求的权限码集合（由 JwtAuthFilter 写入，PermissionAspect 读取）。 */
    public static final String ATTR_PERMS = "ap.perms";

    /** 请求属性键：本次请求的角色码集合。 */
    public static final String ATTR_ROLES = "ap.roles";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final PasswordHasher passwordHasher;

    /** roleId → 权限码集合（进程内缓存，构建代价 = 两次查询）。 */
    private final Map<String, Set<String>> rolePermCache = new ConcurrentHashMap<>();

    /**
     * 登录结果（不包含密码哈希 —— 任何往外传的结构都不该带上它）。
     */
    public record LoginResult(String userId,
                              String tenantId,
                              String username,
                              String displayName,
                              Set<String> roleCodes) {
    }

    /**
     * 校验用户名/密码。
     *
     * <p>「用户不存在」与「密码错误」返回**同一个**错误信息：否则接口会变成
     * 一个用户枚举器（攻击者能据此判断哪些用户名有效）。</p>
     *
     * @throws BizException 401（凭据错误）或 403（账号被停用）
     */
    @Transactional(readOnly = true)
    public LoginResult authenticate(String tenantId, String username, String rawPassword) {
        String tid = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim();
        String uname = username == null ? "" : username.trim();

        SysUser user = userRepository.findByTenantIdAndUsername(tid, uname)
                .orElseThrow(() -> BizException.unauthorized("用户名或密码错误"));

        if (user.getStatus() != SysUserStatus.active) {
            // 停用是管理员主动操作，明确告知比"密码错误"更有用，也不构成枚举风险
            throw BizException.forbidden("账号已停用，请联系管理员");
        }
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }

        Set<String> roles = roleCodesOf(user.getUserId());
        log.info("[rbac] 登录成功：tenant={} user={} roles={}", tid, user.getUsername(), roles);
        return new LoginResult(user.getUserId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), roles);
    }

    /** 用户的角色码集合（按 user_role → role 两步查）。 */
    @Transactional(readOnly = true)
    public Set<String> roleCodesOf(String userId) {
        List<SysUserRole> links = userRoleRepository.findByUserId(userId);
        if (links.isEmpty()) {
            return Set.of();
        }
        List<String> roleIds = links.stream().map(SysUserRole::getRoleId).distinct().toList();
        return roleRepository.findByRoleIdIn(roleIds).stream()
                .map(SysRole::getRoleCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 角色码集合 → 权限码集合（鉴权链路的热路径，走缓存）。
     *
     * <p>角色码可能来自 token（已签发较久），因此这里按 {@code tenantId + roleCode} 反查，
     * 查不到的（角色被删）直接忽略 —— 表现为"该角色已失效"，比抛异常更合适。</p>
     */
    public Set<String> permissionsOf(String tenantId, Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Set.of();
        }
        String tid = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim();
        List<SysRole> roles = roleRepository.findByTenantIdAndRoleCodeIn(tid, roleCodes);
        if (roles.isEmpty()) {
            return Set.of();
        }
        Set<String> perms = new LinkedHashSet<>();
        for (SysRole role : roles) {
            perms.addAll(permissionsOfRole(role.getRoleId()));
        }
        return Set.copyOf(perms);
    }

    /** 单个角色的权限码集合（带缓存）。 */
    public Set<String> permissionsOfRole(String roleId) {
        Set<String> cached = rolePermCache.get(roleId);
        if (cached != null) {
            return cached;
        }
        List<SysRolePermission> links = rolePermissionRepository.findByRoleId(roleId);
        Set<String> perms;
        if (links.isEmpty()) {
            perms = Set.of();
        } else {
            List<String> permIds = links.stream().map(SysRolePermission::getPermId).distinct().toList();
            perms = permissionRepository.findByPermIdIn(permIds).stream()
                    .map(SysPermission::getPermCode)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        // 先查库再 put（而不是 computeIfAbsent 里查库）：避免在 map 的分段锁内做 IO
        rolePermCache.put(roleId, perms);
        return perms;
    }

    // ------------------------------------------------------------------ 缓存维护

    /** 角色权限被修改后调用（阶段 3 的角色编辑接口）。 */
    public void evictRole(String roleId) {
        rolePermCache.remove(roleId);
        log.debug("[rbac] 角色权限缓存已失效：{}", roleId);
    }

    /** 角色被删除时调用；也可用于"权限点被同步移除"后的整体清理。 */
    public void evictAll() {
        rolePermCache.clear();
        log.debug("[rbac] 全部角色权限缓存已清空");
    }

    /** 供测试与诊断查看缓存规模。 */
    public int cachedRoleCount() {
        return rolePermCache.size();
    }
}
