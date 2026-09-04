package com.agentplatform.plugin.sdk.model;

/**
 * 插件钩子触发点（拦截 Agent 运行管线）。
 */
public enum HookPoint {
    /** LLM 调用前（改写/拦截请求） */
    before_llm,
    /** LLM 调用后（改写输出、敏感词过滤、TTS 合成） */
    after_llm,
    /** 输出前（自动回复即在此短路） */
    before_output,
    /** 错误时（降级/兜底） */
    on_error,
    /** 挂载时 */
    on_attach,
    /** 卸载时 */
    on_detach
}