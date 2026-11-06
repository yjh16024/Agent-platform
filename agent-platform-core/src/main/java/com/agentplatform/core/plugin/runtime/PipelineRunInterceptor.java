package com.agentplatform.core.plugin.runtime;

import com.agentplatform.framework.api.InterceptorContext;
import com.agentplatform.framework.api.RunInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把平台插件钩子接成框架的 {@link RunInterceptor}（P2' 插件接缝）。
 *
 * <p>映射关系：{@code before_llm} → {@link #beforeModel} + {@link #shortCircuit}；
 * {@code before_output} → {@link #afterModel}；{@code on_error} → {@link #onError}。</p>
 *
 * <p><b>为什么短路要单独缓存</b>：{@link AgentPipeline#beforeStream} 一次调用同时产出
 * 「改写后的输入」和「短路回复」两个结果，而框架契约把它们拆成两个阶段。
 * 所以这里在 {@code beforeModel} 时把短路内容暂存到 runId 下，随后被 {@code shortCircuit}
 * 取走（取即删，避免长会话里堆积）。</p>
 *
 * <p><b>已知限制</b>：{@code after_llm} 的「附加产物」（如 TTS 音频地址）目前没有去处——
 * 框架的 {@code RunResult} 还没有 artifacts 字段。这里只调用一次以触发副作用，
 * 返回值被丢弃；要完整支持需要给 {@code RunResult} 增列。</p>
 */
@Component
public class PipelineRunInterceptor implements RunInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunInterceptor.class);

    private final AgentPipeline pipeline;
    private final Map<String, String> shortCircuitByRun = new ConcurrentHashMap<>();

    public PipelineRunInterceptor(AgentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public String beforeModel(InterceptorContext ctx) {
        AgentPipeline.StreamHookResult hook =
                pipeline.beforeStream(ctx.agentId(), ctx.runId(), ctx.input());
        if (hook.shortCircuited()) {
            shortCircuitByRun.put(ctx.runId(), hook.shortCircuit());
        }
        return hook.message();
    }

    @Override
    public String shortCircuit(InterceptorContext ctx) {
        return shortCircuitByRun.remove(ctx.runId());
    }

    /**
     * 只触发插件副作用，不改写正文。
     *
     * <p>{@code after_llm} 的返回值是**产物**（音频地址、图表数据），不是替换文本——
     * 所以这里返回 null，由 {@link #artifacts} 走另一条通道带出去。</p>
     */
    @Override
    public String afterModel(InterceptorContext ctx, String reply) {
        return null;
    }

    @Override
    public Map<String, Object> artifacts(InterceptorContext ctx, String reply) {
        Map<String, Object> extras = pipeline.afterStream(ctx.agentId(), ctx.runId(), reply);
        if (extras == null || extras.isEmpty()) {
            return Map.of();
        }
        log.debug("[pipeline] after_llm 产物：{}", extras.keySet());
        return extras;
    }

    @Override
    public String onError(InterceptorContext ctx, RuntimeException error) {
        return pipeline.onStreamError(ctx.agentId(), ctx.runId(), ctx.input(), error);
    }
}
