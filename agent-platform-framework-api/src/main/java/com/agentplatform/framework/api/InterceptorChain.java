package com.agentplatform.framework.api;

import java.util.List;

/**
 * 拦截器链：按注册顺序执行，后一个看到前一个的结果。
 *
 * <p><b>为什么顺序重要</b>：改写与短路是有先后的（后者看到前者改过的输入）；
 * 输出替换同理。这里不引入 {@code @Order} 之类的排序机制——顺序就是列表顺序，
 * 由宿主决定，框架不猜。</p>
 *
 * <p><b>失败语义</b>：某个拦截器抛异常时**不吞掉**，整轮运行失败
 * （流式下变成 {@code RunEvent.Failed}）。插件出错不该被静默忽略，
 * 否则「插件没生效」会变成最难查的一类问题。</p>
 */
public final class InterceptorChain {

    private static final InterceptorChain EMPTY = new InterceptorChain(List.of());

    private final List<RunInterceptor> interceptors;

    public InterceptorChain(List<RunInterceptor> interceptors) {
        this.interceptors = (interceptors == null) ? List.of() : List.copyOf(interceptors);
    }

    public static InterceptorChain empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return interceptors.isEmpty();
    }

    /** 依次执行 beforeModel，返回累积改写后的上下文。 */
    public InterceptorContext applyBefore(InterceptorContext ctx) {
        InterceptorContext current = ctx;
        for (RunInterceptor interceptor : interceptors) {
            String rewritten = interceptor.beforeModel(current);
            if (rewritten != null) {
                current = current.withInput(rewritten);
            }
        }
        return current;
    }

    /** 第一个返回非 null 的拦截器决定短路内容。 */
    public String shortCircuit(InterceptorContext ctx) {
        for (RunInterceptor interceptor : interceptors) {
            String reply = interceptor.shortCircuit(ctx);
            if (reply != null) {
                return reply;
            }
        }
        return null;
    }

    /** 依次执行输出替换；后一个看到前一个的结果。 */
    public String applyAfter(InterceptorContext ctx, String reply) {
        String current = reply;
        for (RunInterceptor interceptor : interceptors) {
            String replaced = interceptor.afterModel(ctx, current);
            if (replaced != null) {
                current = replaced;
            }
        }
        return current;
    }

    /**
     * 汇总产物（R12）：**全部合并**而不是"第一个说了算"——
     * 多个插件各自产出（音频、图表、附件）互不冲突，后者覆盖同名键。
     */
    public java.util.Map<String, Object> artifacts(InterceptorContext ctx, String reply) {
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        for (RunInterceptor interceptor : interceptors) {
            java.util.Map<String, Object> part = interceptor.artifacts(ctx, reply);
            if (part != null && !part.isEmpty()) {
                merged.putAll(part);
            }
        }
        return merged;
    }

    /** 第一个返回非 null 的拦截器提供兜底话术。 */
    public String applyError(InterceptorContext ctx, RuntimeException error) {
        for (RunInterceptor interceptor : interceptors) {
            String fallback = interceptor.onError(ctx, error);
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }
}
