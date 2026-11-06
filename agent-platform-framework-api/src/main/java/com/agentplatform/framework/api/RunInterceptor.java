package com.agentplatform.framework.api;

/**
 * 运行拦截器（B1 的框架侧契约）。
 *
 * <p>它对应平台插件钩子的四种能力，但**不认识「插件」这个概念**：
 * 插件系统是它的一个生产者，别的扩展机制（审计、脱敏、灰度）也能实现它。</p>
 *
 * <table border="1">
 *   <caption>与平台 {@code AgentPipeline} 钩子的对应</caption>
 *   <tr><th>本接口</th><th>平台钩子</th><th>能力</th></tr>
 *   <tr><td>{@link #beforeModel}</td><td>{@code before_llm}</td><td>改写输入</td></tr>
 *   <tr><td>{@link #shortCircuit}</td><td>{@code before_llm}（返回文本）</td><td>跳过模型调用</td></tr>
 *   <tr><td>{@link #afterModel}</td><td>{@code before_output}</td><td>替换最终输出</td></tr>
 *   <tr><td>{@link #onError}</td><td>{@code on_error}</td><td>兜底话术</td></tr>
 * </table>
 *
 * <p>所有方法默认返回 {@code null} 表示「不参与」——实现者只覆写自己关心的那个阶段。</p>
 */
public interface RunInterceptor {

    /** 模型调用前：返回新输入则替换，返回 null 表示不改。 */
    default String beforeModel(InterceptorContext ctx) {
        return null;
    }

    /**
     * 短路：返回非 null 文本则该轮**不调用模型**，直接用此文本作为回复。
     *
     * <p>语义上晚于 {@link #beforeModel}，因此看到的是改写后的输入。</p>
     */
    default String shortCircuit(InterceptorContext ctx) {
        return null;
    }

    /** 模型返回后：返回非 null 则替换最终回复（脱敏 / 合规改写的唯一落点）。 */
    default String afterModel(InterceptorContext ctx, String reply) {
        return null;
    }

    /**
     * 产物上报（R12）：如 TTS 音频地址、图表数据。
     *
     * <p>与 {@link #afterModel} 分开是有原因的：改写是"替换文本"，产物是"附带数据"，
     * 两者的失败语义也不同——产物丢失不该影响用户拿到回复，所以框架只合并、不校验。</p>
     *
     * @return 要并入 {@code RunResult.artifacts} 的键值；空 Map 表示无产物
     */
    default java.util.Map<String, Object> artifacts(InterceptorContext ctx, String reply) {
        return java.util.Map.of();
    }

    /** 运行失败：返回非 null 则作为兜底回复，运行按成功结束。 */
    default String onError(InterceptorContext ctx, RuntimeException error) {
        return null;
    }
}
