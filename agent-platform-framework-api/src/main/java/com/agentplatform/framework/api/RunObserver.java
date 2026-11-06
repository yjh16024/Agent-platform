package com.agentplatform.framework.api;

/**
 * 运行观测出口（M2：观测走标准）。
 *
 * <p>框架**不绑定**任何观测实现：这里只定义"一次运行发生过什么"，
 * 由宿主决定接到 Micrometer / OTel / 自研日志。</p>
 *
 * <p>为什么是三个回调而不是一个：开始、结束、失败是三段生命周期，
 * 失败时没有 {@link RunResult}（用量可能为 0），合并成一个回调会让实现方
 * 到处判断 {@code result == null}。</p>
 *
 * <p><b>实现必须轻量且不抛异常</b>：观测失败不应影响业务。框架侧会在调用处兜住异常。</p>
 */
public interface RunObserver {

    RunObserver NOOP = new RunObserver() {
    };

    /** 运行开始。{@code traceId} 可用于把框架的 span 与宿主链路对齐。 */
    default void runStarted(String runId, String traceId, String agentId, String tenantId) {
    }

    /** 运行成功结束。 */
    default void runCompleted(String runId, long elapsedMs, RunResult.Usage usage) {
    }

    /** 运行失败（含鉴权拒绝、模型报错、工具链异常）。 */
    default void runFailed(String runId, RuntimeException error) {
    }
}
