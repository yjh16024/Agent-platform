package com.agentplatform.plugin.sdk.model;

/**
 * 插件钩子触发点（拦截 Agent 运行管线）。
 *
 * <h3>哪些点会真正触发（2026-09-22 核实）</h3>
 * <ul>
 *   <li>✅ {@link #before_llm} / {@link #after_llm} —— 原有</li>
 *   <li>✅ {@link #before_output} / {@link #on_error} —— <b>本次补全</b>，此前定义了但管线不调用</li>
 *   <li>❌ {@link #on_attach} / {@link #on_detach} —— <b>不会触发，请勿使用</b></li>
 * </ul>
 *
 * <p>关于后两个：{@code Plugin} 接口已有 {@link com.agentplatform.plugin.sdk.Plugin#onAttach}
 * 与 {@link com.agentplatform.plugin.sdk.Plugin#onDetach} 做同样的事，而钩子形式还多绕一层
 * （要先 attach 才注册得上，等于"挂载时"的通知永远迟到）。所以这两项<b>保留枚举值仅为兼容</b>，
 * 宿主不会派发 —— 需要生命周期回调请直接实现 {@code Plugin} 的方法。</p>
 */
public enum HookPoint {
    /** LLM 调用前：返回 String 短路（自动回复）；返回 {@code Map{input}} 改写输入 */
    before_llm,
    /** LLM 调用后：返回 Map 作为<b>附加产物</b>（如 TTS 的 audio_url）。注意它<b>改不了输出文本</b>，改写输出请用 {@link #before_output} */
    after_llm,
    /** 输出前：替换最终输出 —— 敏感词过滤 / PII 脱敏 / 格式化的正确落点 */
    before_output,
    /** 出错时：返回兜底回复（LLM 异常不再上抛）；不返回则异常照常上抛 */
    on_error,
    /** ⚠️ 不会触发（由 {@code Plugin.onAttach} 覆盖），保留仅为兼容 */
    on_attach,
    /** ⚠️ 不会触发（由 {@code Plugin.onDetach} 覆盖），保留仅为兼容 */
    on_detach
}