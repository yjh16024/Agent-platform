package com.agentplatform.common.util;

import java.util.concurrent.Callable;

/**
 * 全链路 Trace 上下文（ThreadLocal 实现，无预览特性依赖）。
 *
 * <h3>为什么从 ScopedValue 换回 ThreadLocal（2026-11-06）</h3>
 * 原实现用 JDK 21 预览特性 {@code ScopedValue}，代价是**整个应用必须用
 * {@code --enable-preview} 编译与运行**。框架要能被第三方应用嵌入，
 * 要求宿主为预览特性买单是不可接受的门槛：预览 API 跨 JDK 版本不保证兼容，
 * 且很多构建流水线默认禁止预览特性。
 *
 * <p>两者在**传播行为**上是一致的：都不跨线程继承。原先
 * {@code TimeoutToolFilter} 就已经手工把上下文重新绑定到虚拟线程里，
 * 因此换成 ThreadLocal 后行为完全不变，调用点一行都不用改。</p>
 *
 * <h3>使用约束</h3>
 * <ul>
 *   <li>必须在 {@link #withContext} 的作用域内读取，作用域结束自动还原（支持嵌套）；</li>
 *   <li><b>不要跨线程依赖隐式传递</b>：新线程里需要重新 {@code withContext}；</li>
 *   <li>不要把这里当成业务上下文——它只装 traceId / runId / tenantId 三个追踪字段。</li>
 * </ul>
 *
 * <pre>{@code
 * TraceContext.withContext(traceId, runId, tenantId, () -> {
 *     String tid = TraceContext.traceId();
 *     // do work
 * });
 * }</pre>
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 获取当前 trace_id，未绑定时返回随机 ID 便于日志追踪。 */
    public static String traceId() {
        String bound = TRACE_ID.get();
        return bound != null ? bound : "trace_" + IdGenerator.random(8);
    }

    /** 获取当前 run_id。 */
    public static String runId() {
        return RUN_ID.get();
    }

    /** 获取当前 tenant_id。 */
    public static String tenantId() {
        String bound = TENANT_ID.get();
        return bound != null ? bound : "default";
    }

    /**
     * 绑定 trace 上下文并同步执行任务。
     *
     * <p>进入时保存旧值、退出时还原，因此嵌套调用不会把外层上下文冲掉。</p>
     */
    public static <T> T withContext(String traceId, String runId, String tenantId, Callable<T> task) {
        String previousTrace = TRACE_ID.get();
        String previousRun = RUN_ID.get();
        String previousTenant = TENANT_ID.get();

        String effectiveTrace = (traceId == null || traceId.isBlank())
                ? (previousTrace != null ? previousTrace : "trace_" + IdGenerator.random(8))
                : traceId;

        TRACE_ID.set(effectiveTrace);
        RUN_ID.set(runId);
        TENANT_ID.set(tenantId == null ? "default" : tenantId);
        try {
            return task.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Trace context task failed", e);
        } finally {
            restore(TRACE_ID, previousTrace);
            restore(RUN_ID, previousRun);
            restore(TENANT_ID, previousTenant);
        }
    }

    /**
     * 绑定 trace 上下文并同步执行（无返回值）。
     */
    public static void withContext(String traceId, String runId, String tenantId, Runnable task) {
        withContext(traceId, runId, tenantId, () -> {
            task.run();
            return null;
        });
    }

    private static void restore(ThreadLocal<String> holder, String previous) {
        if (previous == null) {
            holder.remove();
        } else {
            holder.set(previous);
        }
    }
}
