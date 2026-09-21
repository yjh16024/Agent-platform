package com.agentplatform.core.audit;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.model.entity.SysAuditLog;
import com.agentplatform.model.repository.SysUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志切面：把"谁、何时、对什么、做了什么、结果如何"落库。
 *
 * <h3>为什么自动记录而不是靠注解开启</h3>
 * 切点直接覆盖**所有 {@code @RestController}**，凡是写操作（非 GET）一律记录；
 * {@link AuditLog} 注解只用来**补充可读的动作名**。
 * 理由：如果审计靠注解开启，"忘加注解"就等于"悄悄不审计了" —— 而审计的核心价值恰恰是
 * **不因疏忽而缺失**。宁可动作名难看（回落成 {@code "DELETE /api/v1/..."}），不能有洞。
 *
 * <h3>为什么 {@code @Order(0)} 比权限切面更外层</h3>
 * {@code PermissionAspect} 没有 {@code @Order}（即 {@code LOWEST_PRECEDENCE}），
 * 所以这里给 0 就能保证**先进入、后退出**，从而把"权限被拒绝"的请求也记下来 ——
 * "某人尝试删除用户但被拒绝"这类记录，恰恰是审计里最有价值的部分。
 *
 * <h3>绝不记录请求体（重要）</h3>
 * 请求体里可能是密码（建用户、登录）、API Key（模型配置）。**一旦写进审计表，
 * 密钥就落库了、而且会在审计页面上被展示出来**。所以本切面**只记请求行与结果**，
 * 不碰 body；需要业务语义时用 {@link AuditLog} 注解显式声明。
 *
 * <h3>关于"长连接会挂住切面"的核实结论</h3>
 * {@code POST /api/v1/agent/run} 看起来是 SSE，但它返回的是 {@code ResponseEntity}
 * （body 是 {@code Flux}，运行时才设 {@code TEXT_EVENT_STREAM}），
 * {@code proceed()} **立即返回**、由 Spring 异步订阅，不会阻塞。
 * 所以那个接口**不需要排除**（而且记录"发起运行智能体"本来就有价值）。
 * 这里仍保留返回类型检查，纯粹是防御以后有人用真正的 {@link SseEmitter}。
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class AuditAspect {

    @Value("${agent-platform.audit.enabled:true}")
    private boolean auditEnabled;

    private final AuditService auditService;
    private final SysUserRepository userRepository;

    /** userId → username 的轻量缓存（审计是低频写操作，但同一用户会反复出现）。 */
    private final Map<String, String> usernameCache = new ConcurrentHashMap<>();

    private static final int MAX_UA = 300;
    private static final int MAX_ERR = 500;
    private static final int MAX_URI = 500;
    private static final int MAX_DETAIL = 500;

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!auditEnabled || shouldSkip(pjp)) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            // 组装审计本身也不该抛出去 —— 用 finally 而不是 catch，
            // 是为了让"成功"和"抛异常"两条路径都能留下记录。
            try {
                write(pjp, error, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("[audit] 组装审计记录失败（已忽略）：{}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 记录

    private void write(ProceedingJoinPoint pjp, Throwable error, long durationMs) {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return;
        }
        AuditLog anno = resolveAnnotation(pjp);

        String action = anno != null && !anno.action().isBlank()
                ? anno.action()
                : req.getMethod() + " " + req.getRequestURI();

        SysAuditLog entry = SysAuditLog.builder()
                .auditId(IdGenerator.generate("audit"))
                .tenantId(RbacContext.tenantId())
                .userId(RbacContext.userId())
                .username(resolveUsername(RbacContext.userId()))
                // 角色是"操作当时"的快照：用户后来被调岗不该改写历史记录
                .roles(joinRoles(RbacContext.roles()))
                .action(truncate(action, 100))
                .targetType(anno == null || anno.targetType().isBlank() ? null : anno.targetType())
                .targetId(resolveTargetId(pjp, anno))
                .method(req.getMethod())
                .uri(truncate(req.getRequestURI(), MAX_URI))
                .httpStatus(error == null ? 200 : null)
                .success(error == null)
                .errorMsg(error == null ? null : truncate(error.getMessage(), MAX_ERR))
                .ip(clientIp(req))
                .userAgent(truncate(req.getHeader("User-Agent"), MAX_UA))
                .durationMs(durationMs)
                .detail(buildDetail(pjp, anno))
                .build();

        auditService.record(entry);
    }

    /**
     * 补一句人读得懂的描述。**不包含任何参数值** —— 参数里可能有密钥。
     * 只写"哪个方法、什么动作"，够定位即可。
     */
    private String buildDetail(ProceedingJoinPoint pjp, AuditLog anno) {
        if (anno == null || anno.action().isBlank()) {
            return null;
        }
        return truncate(pjp.getSignature().toShortString(), MAX_DETAIL);
    }

    /** 按注解声明的参数名取目标 ID（取不到就留空，不影响记录本身）。 */
    private String resolveTargetId(ProceedingJoinPoint pjp, AuditLog anno) {
        if (anno == null || anno.targetIdParam().isBlank()
                || !(pjp.getSignature() instanceof MethodSignature sig)) {
            return null;
        }
        String[] names = sig.getParameterNames();
        if (names == null) {
            // 参数名需要编译时保留（-parameters）。拿不到就放弃，不猜测。
            return null;
        }
        Object[] args = pjp.getArgs();
        for (int i = 0; i < names.length && i < args.length; i++) {
            if (anno.targetIdParam().equals(names[i])) {
                Object v = args[i];
                return v == null ? null : truncate(String.valueOf(v), 128);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 跳过判断

    private boolean shouldSkip(ProceedingJoinPoint pjp) {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return true;
        }

        // 1) 只记**写操作**。读操作量级完全不同，全记会把审计表冲垮，也没人查"谁看了列表"。
        String method = req.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // 2) 真正的流式返回：会在响应结束前不返回，记了也没意义
        if (returnsStreaming(pjp)) {
            return true;
        }

        // 3) 登录接口：此时还没有身份（RbacContext 为空），记下来只有一行空用户名，没有价值。
        //    而它的 body 含密码 —— 虽然我们不记 body，但没必要冒这个险。
        if (req.getRequestURI() != null && req.getRequestURI().startsWith("/api/v1/auth/")) {
            return true;
        }

        // 4) 注解显式跳过
        AuditLog anno = resolveAnnotation(pjp);
        return anno != null && anno.skip();
    }

    private boolean returnsStreaming(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature sig)) {
            return false;
        }
        Class<?> ret = sig.getReturnType();
        return SseEmitter.class.isAssignableFrom(ret)
                || ResponseBodyEmitter.class.isAssignableFrom(ret)
                || StreamingResponseBody.class.isAssignableFrom(ret);
    }

    private AuditLog resolveAnnotation(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature sig)) {
            return null;
        }
        AuditLog onMethod = sig.getMethod().getAnnotation(AuditLog.class);
        if (onMethod != null) {
            return onMethod;
        }
        Object target = pjp.getTarget();
        Class<?> type = target != null ? target.getClass() : sig.getDeclaringType();
        return type == null ? null : type.getAnnotation(AuditLog.class);
    }

    // ------------------------------------------------------------------ 小工具

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private String resolveUsername(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String cached = usernameCache.get(userId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String name = "";
        try {
            name = userRepository.findByUserId(userId).map(u -> u.getUsername()).orElse("");
        } catch (Exception e) {
            // 查不到用户名不该让审计失败，留空即可（user_id 才是权威标识）
            log.debug("[audit] 解析用户名失败：{}", e.getMessage());
        }
        usernameCache.put(userId, name);
        return name.isEmpty() ? null : name;
    }

    private static String joinRoles(java.util.List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        return truncate(String.join(",", roles), 200);
    }

    /** 取真实客户端 IP，注意取 X-Forwarded-For 的**第一段**（后面可能被追加伪造值）。 */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return truncate(comma > 0 ? xff.substring(0, comma).trim() : xff.trim(), 64);
        }
        return truncate(req.getRemoteAddr(), 64);
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
