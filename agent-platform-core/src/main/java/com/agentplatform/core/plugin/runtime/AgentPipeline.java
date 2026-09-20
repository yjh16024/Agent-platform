package com.agentplatform.core.plugin.runtime;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 管线（装饰器 + 责任链）。
 * <p>
 * 把插件 Hook 织入 Agent 运行流程，拦截点：
 * <ul>
 *   <li><b>before_llm</b>：改写/拦截请求（自动回复在此短路——返回 String 即直接回复，不调 LLM）</li>
 *   <li><b>after_llm</b>：改写输出、敏感词过滤、TTS 合成（返回 Map 作为附加产物如 audio_url）</li>
 * </ul>
 * </p>
 * <p>Hook 返回约定：</p>
 * <ul>
 *   <li>before_llm 返回 String → 短路（视为预设答复）；返回 null → 透传继续</li>
 *   <li>after_llm 返回 Map → 合并进附加产物；返回 null → 无附加</li>
 * </ul>
 * <p><b>2026-09-20 改为按智能体过滤</b>：{@link #run} 现在必须传 agentId，
 * 只会触发<b>挂在该智能体上</b>的插件钩子（此前是全局触发 —— 挂到 A 的插件会影响 B）。</p>
 * <p><b>另注</b>：本管线目前只在<b>非流式</b>链路（{@code AgentRuntimeService.run()}）被调用；
 * 流式（{@code runStream()}）不经过这里，因此钩子类插件在流式下不会生效。</p>
 */
@Slf4j
@Component
public class AgentPipeline {

    private final ExtensionRegistry extensions;

    public AgentPipeline(ExtensionRegistry extensions) {
        this.extensions = extensions;
    }

    /**
     * 执行管线：before_llm → (LLM) → after_llm。
     *
     * @param agentId     当前智能体 ID（决定触发谁的钩子）
     * @param runId       本次运行 ID（透传给插件，便于插件做关联日志）
     * @param userMessage 用户消息
     * @param llmAction   LLM 调用函数（短链路核心动作）
     */
    public PipelineResult run(String agentId, String runId, String userMessage,
                              Function<String, String> llmAction) {
        // ① before_llm 钩子：自动回复在此短路
        for (AgentHook hook : extensions.hooksAt(HookPoint.before_llm, agentId)) {
            HookContext ctx = new HookContext(userMessage, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            if (result instanceof String directReply) {
                log.info("before_llm hook {} short-circuited run of agent {}", hook.id(), agentId);
                return new PipelineResult(directReply, true, Map.of());
            }
        }

        // ② LLM 推理
        String llmReply = llmAction.apply(userMessage);

        // ③ after_llm 钩子：TTS 合成 / 输出改写
        Map<String, Object> extras = new HashMap<>();
        for (AgentHook hook : extensions.hooksAt(HookPoint.after_llm, agentId)) {
            HookContext ctx = new HookContext(llmReply, agentId, runId, new HashMap<>());
            Object result = safelyInvoke(hook, ctx);
            if (result instanceof Map<?, ?> map) {
                map.forEach((k, v) -> extras.put(String.valueOf(k), v));
            }
        }
        return new PipelineResult(llmReply, false, extras);
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
