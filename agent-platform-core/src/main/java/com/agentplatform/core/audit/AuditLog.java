package com.agentplatform.core.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给接口补充审计语义（可选）。
 *
 * <pre>{@code
 * @AuditLog(action = "删除用户", targetType = "user")
 * @DeleteMapping("/{id}")
 * public ApiResponse<?> deleteUser(@PathVariable String id) { ... }
 * }</pre>
 *
 * <h3>它只是"补充"，不是"开关"</h3>
 * {@link AuditAspect} 会**自动记录所有写操作**（非 GET、非 SSE），
 * 所以**漏加这个注解不会导致审计缺记录** —— 只是记录里的动作名会回落成
 * {@code "DELETE /api/v1/system/users/{id}"} 这种原始形式，可读性差一点。
 *
 * <p>这个取舍是刻意的：如果审计靠注解开启，那"忘加注解"就等于"悄悄不审计了"，
 * 而审计的核心价值恰恰是**不会因为疏忽而缺失**。宁可名字难看，不能有洞。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 业务动作名，如「删除用户」。留空则回落到 {@code "METHOD /path"}。 */
    String action() default "";

    /** 目标类型，如 {@code user} / {@code role} / {@code agent}；配合 targetType 可做"查某对象的操作历史"。 */
    String targetType() default "";

    /** 目标 ID 的**方法参数名**（可选）。指定后切面会尝试从同名参数取实际值填进审计记录。 */
    String targetIdParam() default "";

    /**
     * 是否跳过审计。
     * <p>只该用于"记录本身有害或毫无意义"的场景，例如登录接口 ——
     * 它的请求体含密码。切面另有敏感路径黑名单兜底，这里是给开发者显式表态用的。</p>
     */
    boolean skip() default false;
}
