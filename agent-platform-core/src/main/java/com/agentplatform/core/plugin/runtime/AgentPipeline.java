package com.agentplatform.core.plugin.runtime;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 管线（装饰器 + 责任链）。
 * <p>
 * 把插件 Hook 织入 Agent 运行流程。完整链路：
 * </p>
 *
 * <pre>
 *   before_llm ──(短路则直接返回)──▶ LLM 推理 ──(异常则 on_error 兜底)──▶ after_llm ──▶ before_output ──▶ 返回
 *        │                              │                                  │              │
 *     可改写输入                    可给兜底回复                    只能附加产物      可替换最终输出
 * </pre>
 *
 * <h3>各钩子点的返回值约定（2026-09-22 补全）</h3>
 * <table border="1">
 *   <caption>Hook 返回值语义</caption>
 *   <tr><th>钩子点</th><th>返回值</th><th>语义</th></tr>
 *   <tr><td rowspan="3">before_llm</td><td>String</td><td><b>短路</b>：不调 LLM，直接作为最终回复返回</td></tr>
 *   <tr><td>{@code Map{input: String}}</td><td><b>改写输入</b>：改写后的文本会传给 LLM，且后续钩子看到的是改写后的值</td></tr>
 *   <tr><td>null</td><td>透传，不做任何改动</td></tr>
 *   <tr><td rowspan="2">after_llm</td><td>Map</td><td><b>附加产物</b>：合并进 {@code extras}（如 {@code audio_url}）。<b>改不了输出文本</b></td></tr>
 *   <tr><td>null</td><td>无附加</td></tr>
 *   <tr><td rowspan="2">before_output</td><td>String 或 {@code Map{output: String}}</td><td><b>替换最终输出</b>（脱敏 / 合规改写 / 格式化）</td></tr>
 *   <tr><td>null</td><td>透传，保留 LLM 原输出</td></tr>
 *   <tr><td rowspan="2">on_error</td><td>String 或 {@code Map{reply: String}}</td><td><b>兜底回复</b>：LLM 抛异常时用它作为输出，异常不再上抛</td></tr>
 *   <tr><td>null</td><td>不兜底，异常照常上抛（保持"没有插件就没变化"）</td></tr>
 * </table>
 *
 * <p>
 * 取值容错：Map 形式下 {@code input} 也接受 {@code message}/{@code prompt}，
 * {@code output} 也接受 {@code reply}/{@code text}/{@code content}，
 * 以降低插件作者的猜测成本。
 * </p>
 *
 * <p><b>2026-09-20 改为按智能体过滤</b>：{@link #run} 现在必须传 agentId，
 * 只会触发<b>挂在该智能体上</b>的插件钩子（此前是全局触发 —— 挂到 A 的插件会影响 B）。</p>
 *
 * <p><b>2026-09-22 起流式链路也接入了钩子</b>（此前 {@code runStream()} 完全不走管线，
 * 于是"开了流式开关，插件与工具一起静默失效"）。流式的可用范围**小于**非流式，
 * 但两者并非一刀切：</p>
 * <table border="1">
 *   <caption>流式 / 非流式下各钩子的可用性</caption>
 *   <tr><th>钩子点</th><th>非流式 {@link #run}</th><th>流式 {@link #beforeStream} / {@link #afterStream} / {@link #onStreamError}</th></tr>
 *   <tr><td>{@code before_llm}</td><td>✅ 短路 + 改写</td><td>✅ 短路 + 改写</td></tr>
 *   <tr><td>{@code on_error}</td><td>✅ 兜底</td><td>✅ 兜底</td></tr>
 *   <tr><td>{@code after_llm}</td><td>✅ 附加产物（并入响应）</td>
 *       <td>⚠️ <b>只支持附加产物</b>（{@link #afterStream}，随结束帧下发；不能借此改正文）</td></tr>
 *   <tr><td>{@code before_output}</td><td>✅ 替换输出</td><td>❌ 不生效</td></tr>
 * </table>
 * <p><b>判据是「会不会改动已经显示出去的内容」</b>，而不是「是不是事后执行」：</p>
 * <ul>
 *   <li>{@code before_output} 改的是<b>正文</b>，而正文已逐块推给前端 —— 事后替换只会造成
 *       「日志显示钩子成功、用户看到的仍是原文」。<b>所以流式下应当禁用</b>，
 *       要治理输出请改用 {@code before_llm} 前置改写。</li>
 *   <li>{@code after_llm} 产出的是<b>附加物</b>（{@code audio_url} 等），不碰任何已推送内容，
 *       与随结束帧下发的 {@code toolCalls} 完全同构。此前一并禁掉是过度收紧，
 *       代价是 TTS 这类插件在默认（流式）用法下彻底不可用、且用户无从知晓原因
 *       （2026-09-28 修复）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentPipeline {

    private final ExtensionRegistry extensions;

    public AgentPipeline(ExtensionRegistry extensions) {
        this.extensions = extensions;
    }

    /** Map 形式取值的候选键（不同钩子点语义不同，但都放宽以降低插件作者的猜测成本）。 */
    private static final List<String> INPUT_KEYS = List.of("input", "message", "prompt");
    private static final List<String> OUTPUT_KEYS = List.of("output", "reply", "text", "content");
    private static final List<String> FALLBACK_KEYS = List.of("reply", "output", "fallback");

    /**
     * 执行管线：before_llm → (LLM) → after_llm → before_output。
     *
     * @param agentId     当前智能体 ID（决定触发谁的钩子）
     * @param runId       本次运行 ID（透传给插件，便于插件做关联日志）
     * @param userMessage 用户消息
     * @param llmAction   LLM 调用函数（短链路核心动作）
     */
    public PipelineResult run(String agentId, String runId, String userMessage,
                              Function<String, String> llmAction) {
        // ① before_llm：短路（返回 String）或改写输入（返回 Map{input}）
        //    改写后要让后续钩子看到新值，所以用 effectiveMessage 逐轮推进
        String effectiveMessage = userMessage;
        for (AgentHook hook : extensions.hooksAt(HookPoint.before_llm, agentId)) {
            HookContext ctx = new HookContext(effectiveMessage, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            if (result instanceof String directReply) {
                log.info("before_llm hook {} short-circuited run of agent {}", hook.id(), agentId);
                return new PipelineResult(directReply, true, Map.of());
            }
            String rewritten = pickString(result, INPUT_KEYS);
            if (rewritten != null) {
                log.info("before_llm hook {} rewrote input of agent {} ({} → {} chars)",
                        hook.id(), agentId, effectiveMessage.length(), rewritten.length());
                effectiveMessage = rewritten;
            }
        }

        // ② LLM 推理：异常交给 on_error 钩子兜底；无人兜底则原样上抛（保持旧行为）
        String llmReply;
        try {
            llmReply = llmAction.apply(effectiveMessage);
        } catch (Exception e) {
            String fallback = runErrorHooks(agentId, runId, effectiveMessage, e);
            if (fallback == null) {
                throw e;
            }
            Map<String, Object> extras = new HashMap<>();
            extras.put("error_handled", Boolean.TRUE);
            extras.put("error_message", e.getMessage());
            return new PipelineResult(fallback, false, extras);
        }

        // ③ after_llm：只收「附加产物」，输出文本此时仍未被改写
        Map<String, Object> extras = collectAfterLlm(agentId, runId, llmReply);

        // ④ before_output：替换最终输出（放在最后，保证"输出治理"拿到的就是用户将看到的那一版）
        String finalReply = llmReply;
        for (AgentHook hook : extensions.hooksAt(HookPoint.before_output, agentId)) {
            HookContext ctx = new HookContext(finalReply, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            String replaced = result instanceof String s ? s : pickString(result, OUTPUT_KEYS);
            if (replaced != null && !replaced.equals(finalReply)) {
                log.info("before_output hook {} rewrote output of agent {} ({} → {} chars)",
                        hook.id(), agentId, finalReply.length(), replaced.length());
                finalReply = replaced;
            }
        }

        return new PipelineResult(finalReply, false, extras);
    }

    /** 流式链路的钩子返回值：改写后的消息 +（可选的）短路回复。 */
    public record StreamHookResult(String message, String shortCircuit) {

        public boolean shortCircuited() {
            return shortCircuit != null;
        }
    }

    /**
     * 流式链路的**前置**钩子：只跑 {@code before_llm}（短路 / 改写输入）。
     *
     * <h3>为什么流式不能直接复用 {@link #run}</h3>
     * {@link #run} 的语义是「包住一次完整的 LLM 调用」，其中 {@code after_llm} 与
     * {@code before_output} 都建立在「已经拿到完整输出」这个前提上。而流式场景下文本正在
     * 逐块推给前端 —— **后端此时再改写也改不动已经显示出去的内容**。硬套 {@code run}
     * 只会得到更坏的结果：钩子看起来"执行成功"了（日志正常、返回值也拿到了），
     * 但用户看到的仍是未治理的原文，排查时极难定位。
     *
     * <p>所以流式链路**只承诺两件事**：调用前的<b>改写 / 短路</b>，以及失败时的<b>兜底</b>
     * （见 {@link #onStreamError}）。{@code after_llm} / {@code before_output} 在流式下
     * <b>不生效</b>，需要在流式下做输出治理的插件应改用「前置改写」或要求非流式调用。</p>
     */
    public StreamHookResult beforeStream(String agentId, String runId, String userMessage) {
        String effectiveMessage = userMessage;
        for (AgentHook hook : extensions.hooksAt(HookPoint.before_llm, agentId)) {
            HookContext ctx = new HookContext(effectiveMessage, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            if (result instanceof String directReply) {
                log.info("before_llm hook {} short-circuited streaming run of agent {}",
                        hook.id(), agentId);
                return new StreamHookResult(effectiveMessage, directReply);
            }
            String rewritten = pickString(result, INPUT_KEYS);
            if (rewritten != null) {
                log.info("before_llm hook {} rewrote streaming input of agent {} ({} → {} chars)",
                        hook.id(), agentId, effectiveMessage.length(), rewritten.length());
                effectiveMessage = rewritten;
            }
        }
        return new StreamHookResult(effectiveMessage, null);
    }

    /**
     * 流式链路的**收尾**钩子：文本已全部推给前端后，收集 {@code after_llm} 的附加产物。
     *
     * <h3>★ 为什么流式下可以跑 after_llm，而 before_output 仍然不行</h3>
     * 两者在流式下的处境<b>根本不同</b>，旧实现把它们一起禁掉是过度收紧：
     * <ul>
     *   <li>{@code before_output} 是<b>改写正文</b> —— 文本已经逐块发给前端了，改不动，
     *       硬跑只会得到「日志显示成功、用户看到的还是原文」。<b>继续禁用是对的。</b></li>
     *   <li>{@code after_llm} 是<b>附加产物</b>（典型就是 TTS 的 {@code audio_url}）——
     *       它不碰任何已推送的内容，只是随结束帧多带一段数据。这与 {@code toolCalls}
     *       走的是同一条通道（见 {@code RunStreamEvent.completed}），后者一直就在这么做。</li>
     * </ul>
     * 所以这里放行 {@code after_llm}：<b>它让 TTS 在默认的流式用法下可用</b> ——
     * 否则用户开了流式（默认就开着）就永远等不到语音，且从界面上完全看不出为什么。
     *
     * @param finalReply 本轮的完整回复文本（流式内容拼起来的结果）—— 即"要合成的文本"
     * @return 合并后的 extras；无人贡献时为空 Map（不是 null，调用方无需判空）
     */
    public Map<String, Object> afterStream(String agentId, String runId, String finalReply) {
        return collectAfterLlm(agentId, runId, finalReply);
    }

    /**
     * 执行 {@code after_llm} 钩子并合并返回值。
     *
     * <p>抽出来是因为非流式（{@link #run} 第 ③ 步）与流式收尾（{@link #afterStream}）
     * 要做的事完全相同 —— 分开写迟早会漏掉其中一条的行为差异。</p>
     */
    private Map<String, Object> collectAfterLlm(String agentId, String runId, String llmReply) {
        Map<String, Object> extras = new HashMap<>();
        for (AgentHook hook : extensions.hooksAt(HookPoint.after_llm, agentId)) {
            HookContext ctx = new HookContext(llmReply, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            if (result instanceof Map<?, ?> map) {
                map.forEach((k, v) -> extras.put(String.valueOf(k), v));
            }
        }
        return extras;
    }

    /**
     * 流式链路的**兜底**钩子：LLM 抛异常时问 {@code on_error} 要一段话术。
     *
     * @return 兜底回复；无人兜底时返回 null（调用方据此决定是否把错误透传出去）
     */
    public String onStreamError(String agentId, String runId, String userMessage, Exception e) {
        return runErrorHooks(agentId, runId, userMessage, e);
    }

    /**
     * 提示"该钩子在流式链路下不会生效"。
     *
     * <p>流式与非流式的能力差异是<b>真实存在</b>的，但插件作者看不到这条差异时，
     * 最典型的困惑是"本地测试正常、一开流式开关就失效"（聊天页的流式开关默认关闭，
     * 所以很容易在某次演示时才突然发现）。这里统一打一条 WARN 把差异显性化。</p>
     */
    public void warnStreamingUnsupported(String agentId, String hookPoint) {
        if (!extensions.hooksAt(HookPoint.valueOf(hookPoint), agentId).isEmpty()) {
            log.warn("[pipeline] 钩子点 {} 在流式链路下不生效（agent={}）："
                            + "流式内容已逐步推送给前端，无法在生成后再改写。"
                            + "若必须治理输出，请改用 before_llm 前置改写，或要求非流式调用。",
                    hookPoint, agentId);
        }
    }

    /**
     * 触发 on_error 钩子，取第一个非空兜底回复。
     *
     * <p>把异常同时放进 {@code metadata}（结构化，便于插件按类型分支）与 {@code input}
     * （字符串，便于只要一段提示的简单插件直接用）：</p>
     * <ul>
     *   <li>{@code metadata: {exception: Throwable, error_message: String, original_input: String}}</li>
     *   <li>{@code input: "错误信息字符串"}</li>
     * </ul>
     *
     * @return 兜底回复；没有任何插件给出兜底时返回 null（调用方据此决定是否继续上抛）
     */
    private String runErrorHooks(String agentId, String runId, String effectiveMessage, Exception e) {
        for (AgentHook hook : extensions.hooksAt(HookPoint.on_error, agentId)) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("exception", e);
            meta.put("error_message", e.getMessage());
            meta.put("original_input", effectiveMessage);
            HookContext ctx = new HookContext(
                    e.getMessage() == null ? e.toString() : e.getMessage(), agentId, runId, meta);

            Object result = safelyInvoke(hook, ctx);
            String fallback = pickString(result, FALLBACK_KEYS);
            if (fallback != null) {
                log.info("on_error hook {} supplied fallback reply for agent {} (cause: {})",
                        hook.id(), agentId, e.getMessage());
                return fallback;
            }
        }
        return null;
    }

    /**
     * 从钩子返回值里取一个非空字符串。
     *
     * <p>兼容两种写法：直接返回 String，或返回 {@code Map} 并把文本放在约定键上。
     * 只认约定键而不是"取 Map 里第一个 String" —— 后者会让 {@code after_llm} 那种
     * 附加产物（如 {@code {audio_url: ...}}）被误当成文本改写。</p>
     */
    private static String pickString(Object result, List<String> keys) {
        if (result instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (result instanceof Map<?, ?> map) {
            for (String k : keys) {
                Object v = map.get(k);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
        }
        return null;
    }

    private Object safelyInvoke(AgentHook hook, HookContext ctx) {
        try {
            return hook.invoke(ctx);
        } catch (Exception e) {
            log.error("Hook {} invoke error: {}", hook.id(), e.getMessage(), e);
            return null; // 单个 Hook 失败不影响主流程（降级跳过）
        }
    }
}
