package com.agentplatform.framework.api;

/**
 * 配额策略（B2：配额从「内核必需」变成「宿主可选」）。
 *
 * <p>配额是**运营者**的概念，不是运行时的前提。框架默认 {@link #NOOP} 放行，
 * 平台注入自己的实现（限额表 + Redis 计数）。</p>
 */
@FunctionalInterface
public interface QuotaPolicy {

    /** 不限额：默认值。 */
    QuotaPolicy NOOP = (tenantId, metric, amount) -> {
    };

    /**
     * 预占一次用量；超额时抛出异常由调用方处理。
     *
     * @param tenantId 租户
     * @param metric   计量项，见 {@link Metrics}
     * @param amount   数量（一次运行通常为 1）
     */
    void reserve(String tenantId, String metric, long amount);

    /** 计量项常量：与平台 {@code quota} 的类型保持一致。 */
    final class Metrics {

        public static final String MODEL_CALLS = "model_calls";
        public static final String TOKENS = "tokens";

        private Metrics() {
        }
    }
}
