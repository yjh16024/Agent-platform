package com.agentplatform.common.util;

import java.util.concurrent.Callable;

/**
 * 全链路 Trace 上下文（JDK 21 ScopedValue 实现）。
 * <p>
 * 使用 {@link ScopedValue} 替代 ThreadLocal 传递 trace_id / run_id / tenant_id，
 * 纳秒级绑定、作用域结束自动失效，在虚拟线程环境下根治「上下文串号」与内存泄漏问题。
 * </p>
 * <p>
 * 用法：
 * <pre>{@code
 * TraceContext.withContext(traceId, runId, tenantId, () -> {
 *     String tid = TraceContext.traceId();
 *     // do work
 * });
 * }</pre>
 * </p>
 */
public final class TraceContext {

    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> RUN_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();

    private TraceContext() {
    }

    /** 获取当前 trace_id，未绑定时返回随机 ID 便于日志追踪。 */
    public static String traceId() {
        return TRACE_ID.isBound() ? TRACE_ID.get() : "trace_" + IdGenerator.random(8);
    }

    /** 获取当前 run_id。 */
    public static String runId() {
        return RUN_ID.isBound() ? RUN_ID.get() : null;
    }

    /** 获取当前 tenant_id。 */
    public static String tenantId() {
        return TENANT_ID.isBound() ? TENANT_ID.get() : "default";
    }

    /**
     * 绑定 trace 上下文并同步执行任务。
     */
    public static <T> T withContext(String traceId, String runId, String tenantId, Callable<T> task) {
        try {
            return ScopedValue.where(TRACE_ID, traceId == null ? TraceContext.traceId() : traceId)
                    .where(RUN_ID, runId)
                    .where(TENANT_ID, tenantId == null ? "default" : tenantId)
                    .call(task);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Trace context task failed", e);
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
}