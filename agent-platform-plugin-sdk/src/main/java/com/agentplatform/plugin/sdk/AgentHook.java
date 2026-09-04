package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.HookPoint;

/**
 * 运行钩子 SPI（自动回复 / TTS 等即在此实现）。
 * <p>
 * 拦截 Agent 运行管线：{@code before_llm}（改写/拦截请求）、{@code after_llm}
 * （改写输出、敏感词过滤）、{@code before_output}（自动回复即在此钩子触发——
 * 无 LLM 调用即直接返回预设响应）、{@code on_error}（降级/兜底）。
 * </p>
 */
public interface AgentHook extends Plugin {

    /**
     * 钩子触发点。
     */
    HookPoint point();

    /**
     * 执行钩子逻辑。
     *
     * @param ctx 钩子上下文（含输入数据）
     * @return 处理结果（可短路后续流程）
     */
    Object invoke(HookContext ctx);
}