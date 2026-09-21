package com.agentplatform.core.security.rbac;

import com.agentplatform.common.exception.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限切面测试：开关语义、方法级/类级注解优先级、放行、拒绝、fail-closed。
 */
class PermissionAspectTest {

    private final PermissionAspect aspect = new PermissionAspect();

    /** 用**真实的注解**（而不是 mock 出来的注解实例）来验证解析逻辑，避免测试自己骗自己。 */
    static class PlainTarget {
        @RequiresPermission("agent:read")
        public void readOp() {
        }
    }

    @RequiresPermission("model:manage")
    static class ClassLevelTarget {
        /** 无方法级注解 → 应继承类级的 model:manage。 */
        public void inheritClassLevel() {
        }

        /** 有方法级注解 → 应覆盖类级。 */
        @RequiresPermission("agent:read")
        public void overriddenByMethod() {
        }
    }

    private ProceedingJoinPoint pjpFor(Class<?> type, String methodName, AtomicBoolean proceeded) {
        try {
            Method method = type.getMethod(methodName);
            MethodSignature sig = mock(MethodSignature.class);
            when(sig.getMethod()).thenReturn(method);
            when(sig.toShortString()).thenReturn(type.getSimpleName() + "." + methodName + "()");
            when(sig.getDeclaringType()).thenReturn(type);

            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.getSignature()).thenReturn(sig);
            when(pjp.getTarget()).thenReturn(type.getDeclaredConstructor().newInstance());
            // 必须用 doAnswer().when(mock).proceed()：写成 when(pjp.proceed()) 会**真的调用** proceed()，
            // 而它不仅会绕过 mock、还把"未报告的 Throwable"带到编译期。
            doAnswer(inv -> {
                proceeded.set(true);
                return null;
            }).when(pjp).proceed();
            return pjp;
        } catch (Throwable e) {
            // 收 Throwable 而不是 Exception：mock 上的 `proceed()` 在编译期就声明抛 Throwable
            // （即使写成 doAnswer().when(mock).proceed()，那个 `.proceed()` 仍是普通方法调用），
            // 而这个纯构造辅助方法不该把 checked 异常传染给每个测试方法。
            throw new IllegalStateException(e);
        }
    }

    private void givenPermissions(Set<String> perms) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RbacService.ATTR_PERMS, perms);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        // 不清理会污染同 JVM 内的其它测试（RequestContextHolder 是 ThreadLocal）
        RequestContextHolder.resetRequestAttributes();
    }

    /** 开关关闭时注解必须被**完全忽略** —— 这是"不影响既有行为"的保证。 */
    @Test
    void disabledRbacIgnoresAnnotationEntirely() throws Throwable {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", false);
        AtomicBoolean proceeded = new AtomicBoolean(false);
        // 故意不给任何权限、也不设请求上下文
        aspect.check(pjpFor(PlainTarget.class, "readOp", proceeded));
        assertTrue(proceeded.get(), "关闭开关时应直接执行原方法");
    }

    @Test
    void allowsWhenPermissionGranted() throws Throwable {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of("agent:read", "agent:write"));
        AtomicBoolean proceeded = new AtomicBoolean(false);

        aspect.check(pjpFor(PlainTarget.class, "readOp", proceeded));

        assertTrue(proceeded.get());
    }

    @Test
    void rejectsWhenPermissionMissing() {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of("kb:read"));

        BizException e = assertThrows(BizException.class,
                () -> aspect.check(pjpFor(PlainTarget.class, "readOp", new AtomicBoolean())));

        assertEquals("FORBIDDEN", e.getErrorCode());
    }

    /** 类级注解：方法没写注解时生效。 */
    @Test
    void classLevelAnnotationAppliesToMethodWithoutItsOwn() {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of("agent:read")); // 只有读权限，类级要的是 model:manage

        BizException e = assertThrows(BizException.class,
                () -> aspect.check(pjpFor(ClassLevelTarget.class, "inheritClassLevel", new AtomicBoolean())));

        assertEquals("FORBIDDEN", e.getErrorCode(), "类级 model:manage 应生效");
    }

    /** 方法级注解覆盖类级：只满足方法级要求即可通过。 */
    @Test
    void methodLevelOverridesClassLevel() throws Throwable {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of("agent:read")); // 没有 model:manage，但方法级只要 agent:read
        AtomicBoolean proceeded = new AtomicBoolean(false);

        aspect.check(pjpFor(ClassLevelTarget.class, "overriddenByMethod", proceeded));

        assertTrue(proceeded.get(), "方法级应覆盖类级");
    }

    /** 多权限是 AND 语义：只满足其中一个也必须拒绝。 */
    @Test
    void multiplePermissionsRequireAll() {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of("agent:read"));

        BizException e = assertThrows(BizException.class, () -> {
            // 借 PlainTarget 的方法构造一个"方法级要两个权限"的场景
            Method m = MultiPermTarget.class.getMethod("needsTwo");
            MethodSignature sig = mock(MethodSignature.class);
            when(sig.getMethod()).thenReturn(m);
            when(sig.toShortString()).thenReturn("needsTwo()");
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.getSignature()).thenReturn(sig);
            when(pjp.getTarget()).thenReturn(new MultiPermTarget());
            doAnswer(inv -> null).when(pjp).proceed();
            aspect.check(pjp);
        });

        assertEquals("FORBIDDEN", e.getErrorCode());
    }

    static class MultiPermTarget {
        @RequiresPermission({"agent:read", "user:manage"})
        public void needsTwo() {
        }
    }

    /**
     * 没有请求上下文（异步/虚拟线程里调用）→ 权限为空 → 拒绝。
     * 有意的 fail-closed：宁可拒绝，也不能因为"读不到权限"就默认放行。
     */
    @Test
    void failsClosedWithoutRequestContext() {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        RequestContextHolder.resetRequestAttributes();

        assertThrows(BizException.class,
                () -> aspect.check(pjpFor(PlainTarget.class, "readOp", new AtomicBoolean())));
    }

    @Test
    void noAnnotationAnywhereMeansNoCheck() throws Throwable {
        ReflectionTestUtils.setField(aspect, "rbacEnabled", true);
        givenPermissions(Set.of());
        AtomicBoolean proceeded = new AtomicBoolean(false);

        aspect.check(pjpFor(Object.class, "toString", proceeded));

        assertTrue(proceeded.get(), "无注解的类/方法不应被拦截");
    }
}
