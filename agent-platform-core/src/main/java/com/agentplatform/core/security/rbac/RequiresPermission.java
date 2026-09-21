package com.agentplatform.core.security.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的权限码，如：
 * <pre>{@code
 * @RequiresPermission("user:manage")
 * @PostMapping("/users")
 * public ApiResponse<?> createUser(...) { ... }
 * }</pre>
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@link #value()} 有多个时是 **AND**（全部满足才放行）。需要"任一即可"时，
 *       请拆成多个方法或改用更粗的权限码 —— 先刻意不支持 OR，避免"看起来满足了其实没有"
 *       这类难以察觉的授权漏洞；</li>
 *   <li>**没标注解的接口 = 登录即可访问**，这是本方案"零侵入现有代码"的关键：
 *       17 个既有 Controller 不改一行，行为与现在完全一致，可以按模块逐个加管控。</li>
 * </ul>
 *
 * <h3>生效条件</h3>
 * 仅在 {@code agent-platform.security.rbac.enabled=true} 时由 {@link PermissionAspect} 校验；
 * 关闭时注解**完全被忽略**（而不是"校验失败"），保证本地开发与单测不受影响。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** 所需权限码（取自 {@link RbacPermission} 的 {@code code()}），多个之间为 AND 关系。 */
    String[] value();
}
