package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.notification.NotificationService;
import com.agentplatform.core.plugin.runtime.DomainEventBus;
import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 站内通知（可选：未注入时静默跳过）。
     *
     * <p>用途：<b>越权被拒时告知触发者</b> —— 否则用户只会看到操作失败、不知道为什么，
     * 反复点同一个按钮。这是"反馈缺失"而非"安全问题"。</p>
     *
     * <p>另一侧（<b>告警管理员</b>"有人尝试越权"）需要"按角色投递"能力，而当前通知模型
     * 是点对点、没有群发 —— 故留待后续引入角色投递后再做，此处不假装支持。</p>
     */
    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * 领域事件总线（可选：未注入时静默跳过）。
     * <p>权限被拒同样属于<b>租户级事件</b>（可能发生在任何接口上、无 agent 维度），
     * 因此不会派发给插件订阅者 —— 见 {@code DomainEventBus} 的派发规则。</p>
     */
    @Autowired(required = false)
    private DomainEventBus domainEventBus;

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
            // 告知触发者"为什么点不动"：否则界面只表现为操作失败且无原因，用户会反复重试。
            // 注意【不】把缺少的权限码写进通知正文 —— 那是内部实现细节，对普通用户没有意义，
            // 也等于把权限模型暴露给不该看到的人。日志里已经记了明细，排查够用。
            notifyForbidden(target);
            publishPermissionDenied(target, missing);
            throw BizException.forbidden("缺少权限：" + String.join("、", missing));
        }

        // 通过时也留一条 debug：这是验证"切面在真实容器里真的被执行了"的唯一线索。
        // AOP 静默失效（切面没被织入）与"校验通过"在外部表现完全一样，没有这条日志就只能靠猜。
        log.debug("[rbac] 通过 {} 的权限校验（持有 {}）", pjp.getSignature().toShortString(), granted);
        return pjp.proceed();
    }

    /**
     * 发送"权限不足"通知（失败静默）。
     *
     * <p>刻意<b>只告知结果、不暴露细节</b>：正文里不含缺失的权限码 ——
     * 那属于内部实现（日志里已有明细），写给用户只会造成困惑，也等于把权限模型透露出去。</p>
     *
     * @param target 被拒绝的方法（仅用于日志关联，不进通知正文）
     */
    private void notifyForbidden(String target) {
        if (notificationService == null) {
            return;
        }
        try {
            notificationService.notifyCurrent(NotificationType.security, NotificationLevel.warn,
                    "操作被拒绝：权限不足",
                    "你尝试执行的操作需要更高权限，已被系统拦截。如确需该权限，请联系管理员。",
                    null);
            log.debug("[rbac] 已就 {} 的拒绝向触发者发送通知", target);
        } catch (Exception e) {
            log.debug("发送权限拒绝通知失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 发布「权限被拒」领域事件（失败静默）。
     *
     * <p>租户级事件（无 agentId）→ <b>不派发给插件订阅者</b>，理由同配额事件：
     * 权限拒绝可能发生在任何接口上、没有 agent 维度，派发等于让任意智能体上的插件
     * 监听全租户的拒绝行为。</p>
     *
     * @param missing 缺失的权限码 —— 只进事件、<b>不进用户可见的通知正文</b>（内部细节）
     */
    private void publishPermissionDenied(String target, List<String> missing) {
        if (domainEventBus == null) {
            return;
        }
        try {
            domainEventBus.publish(DomainEvent.ofTenant(EventTypes.PERMISSION_DENIED,
                    RbacContext.tenantId(),
                    DomainEvent.payload(
                            "target", target,
                            "user_id", RbacContext.userId(),
                            "missing", String.join(",", missing))));
        } catch (Exception e) {
            log.debug("发布权限拒绝事件失败（已忽略）：{}", e.getMessage());
        }
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
