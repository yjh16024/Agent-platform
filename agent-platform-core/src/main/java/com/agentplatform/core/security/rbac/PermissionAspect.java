package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link RequiresPermission} 的校验切面。
 *
 * <h3>为什么用 {@code @Around} 而不是 {@code @Before}</h3>
 * 因为要支持**类级注解 + 方法级覆盖**：{@code @Before} 配 {@code @annotation(..)} 切点只能匹配到
 * 方法级注解，无法表达"这个方法没写注解，就按它所在类的注解来"。
 * 用 {@code @Around} 手动解析注解优先级，逻辑直白且不依赖切点表达式的小把戏。
 *
 * <h3>类级 + 方法级的语义</h3>
 * <ul>
 *   <li>方法上有 {@code @RequiresPermission} → **用方法上的**（覆盖类级）；</li>
 *   <li>方法上没有、类上有 → 用类上的。控制器里"整类同一个权限码"的场景据此只需写一行；
 *       而且**将来新增的方法会自动继承类级管控**，不会因为忘加注解而裸奔；</li>
 *   <li>两处都没有 → 不校验（即"登录即可访问"，这是本方案零侵入既有代码的关键）。</li>
 * </ul>
 *
 * <h3>为什么失败抛 {@link BizException#forbidden}</h3>
 * 项目有全局异常处理器把 {@code BizException} 的 errorCode 映射成响应体的 {@code code}
 * （{@code FORBIDDEN} → HTTP 403）。直接 {@code response.setStatus} 会绕过统一响应格式，
 * 前端拿到的东西与其它接口不一致。
 *
 * <h3>开关关闭时</h3>
 * 直接 {@code proceed()}，连"权限集合是否为空"都不判断 —— 与历史行为逐字节一致
 * （本机开发、单测、桌面 embedded 都不受影响）。
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Value("${agent-platform.security.rbac.enabled:false}")
    private boolean rbacEnabled;

    @Around("@annotation(com.agentplatform.core.security.rbac.RequiresPermission)"
            + " || @within(com.agentplatform.core.security.rbac.RequiresPermission)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        if (!rbacEnabled) {
            return pjp.proceed();
        }

        RequiresPermission required = resolveAnnotation(pjp);
        if (required == null) {
            return pjp.proceed();
        }

        Set<String> granted = RbacContext.permissions();
        List<String> missing = new ArrayList<>();
        for (String need : required.value()) {
            if (!granted.contains(need)) {
                missing.add(need);
            }
        }
        if (!missing.isEmpty()) {
            String target = pjp.getSignature().toShortString();
            log.warn("[rbac] 拒绝访问 {}：缺少权限 {}（当前持有 {}）", target, missing, granted);
            throw BizException.forbidden("缺少权限：" + String.join("、", missing));
        }

        // 通过时也留一条 debug：这是验证"切面在真实容器里真的被执行了"的唯一线索。
        // AOP 静默失效（切面没被织入）与"校验通过"在外部表现完全一样，没有这条日志就只能靠猜。
        log.debug("[rbac] 通过 {} 的权限校验（持有 {}）", pjp.getSignature().toShortString(), granted);
        return pjp.proceed();
    }

    /**
     * 方法级注解优先；没有则回落到类级注解。
     *
     * <p>取类时优先 {@code getTarget().getClass()}（真实目标类），拿不到才退回
     * {@code getDeclaringType()} —— 后者在"方法声明于接口/父类"时可能不是带注解的那个类。</p>
     */
    private RequiresPermission resolveAnnotation(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature sig)) {
            return null;
        }
        RequiresPermission onMethod = sig.getMethod().getAnnotation(RequiresPermission.class);
        if (onMethod != null) {
            return onMethod;
        }
        Object target = pjp.getTarget();
        Class<?> type = target != null ? target.getClass() : sig.getDeclaringType();
        return type == null ? null : type.getAnnotation(RequiresPermission.class);
    }
}
