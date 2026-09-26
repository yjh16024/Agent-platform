package com.example.plugin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;

/**
 * 类型五：<b>错误兜底钩子</b>（{@code on_error}）—— LLM 抛异常时给出兜底回复。
 *
 * <p>这是 2026-09-22 新补全的钩子点。此前 LLM 一旦失败（厂商限流、密钥失效、
 * 网络抖动、模型名写错），用户拿到的是 500；现在插件可以在中间把它收成一句人话。</p>
 *
 * <h3>怎么拿到错误信息</h3>
 * <p>宿主会把上下文塞进 {@link HookContext#metadata()}：</p>
 * <ul>
 *   <li>{@code exception} —— 原始 {@link Throwable}（可按类型分支，但<b>不建议</b>依赖具体异常类：
 *       那属于核心实现细节，会随重构变化）；</li>
 *   <li>{@code error_message} —— 异常消息字符串（推荐用它做判断）；</li>
 *   <li>{@code original_input} —— 本次的用户输入（便于给出"针对这句话"的兜底）。</li>
 * </ul>
 * <p>{@link HookContext#input()} 此时是错误消息字符串（给"只想要一段提示"的简单插件直接用）。</p>
 *
 * <h3>返回值约定</h3>
 * <ul>
 *   <li>返回 {@code String} = <b>兜底回复</b>，异常不再上抛（本插件即此用法）；</li>
 *   <li>也可返回 {@code Map{reply: "..."}}（还接受 {@code output}/{@code fallback} 作为键）；</li>
 *   <li>返回 {@code null} = <b>不兜底</b>，异常照常上抛（多插件时可用于"我只处理限流这一类"）。</li>
 * </ul>
 *
 * <h3>兜底之后会发生什么（宿主侧，2026-09-22 一并处理）</h3>
 * <p>因为异常不再上抛，{@code AgentRuntimeService} 的 catch 不会触发，所以宿主会额外补记一条
 * {@code run.degraded} 的 WARN 日志 —— <b>否则"失败"会从日志与审计里凭空消失，
 * 而用户实际收到的是兜底话术，事后排查会对不上账</b>。响应里也会带
 * {@code extras.error_handled = true} 与 {@code extras.error_message}。</p>
 *
 * <p>{@code on_error} 在<b>流式与非流式下都生效</b>（2026-09-22 起；流式链路走
 * {@code AgentPipeline.onStreamError}）。完整生效矩阵见 {@link AgentHook} 类注释。</p>
 */
public class GracefulFallbackPlugin implements AgentHook {

    /** 必须与 manifests/graceful-fallback.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_graceful_fallback";

    /** 默认兜底话术（可被 config.fallback_text 覆盖）。 */
    private static final String DEFAULT_FALLBACK =
            "抱歉，我这边的模型服务暂时不可用，请稍后再试一次。如果反复出现，请联系管理员检查模型配置。";

    private volatile String fallbackText = DEFAULT_FALLBACK;

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        this.fallbackText = ctx.configAsString("fallback_text", DEFAULT_FALLBACK);
    }

    @Override
    public void onDetach(PluginContext ctx) {
        this.fallbackText = DEFAULT_FALLBACK;
    }

    @Override
    public HookPoint point() {
        return HookPoint.on_error;
    }

    @Override
    public Object invoke(HookContext ctx) {
        String message = ctx.input() == null ? "" : ctx.input().toString();
        String cause = "";
        Throwable e = ctx.metadata().get("exception") instanceof Throwable t ? t : null;
        if (e != null) {
            cause = e.getClass().getSimpleName();
        }

        // 演示：按错误信息做粗分类给出更贴切的话术。
        // 刻意匹配消息文本而不是异常类型 —— 类型是核心实现细节，会随重构变化。
        String lower = message.toLowerCase();
        if (lower.contains("quota") || lower.contains("exceed")) {
            return "当前租户的模型调用额度已用尽，请联系管理员调整配额后再试。";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "模型响应超时了，稍后重试通常就能恢复。";
        }
        if (lower.contains("unauthorized") || lower.contains("401") || lower.contains("api key")) {
            return "模型凭据似乎无效，请管理员在「模型配置」里检查该厂商的 API Key。";
        }

        // 兜底：统一话术；cause 只用于日志侧关联（这里拼进话术末尾便于演示时肉眼确认触发了哪条）。
        return cause.isEmpty() ? fallbackText : fallbackText + "（错误类型：" + cause + "）";
    }
}
