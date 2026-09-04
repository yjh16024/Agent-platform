package com.agentplatform.plugin.sdk;

import java.util.Map;

/**
 * 钩子上下文（Hook 被触发时的输入）。
 *
 * @param input    输入数据（取决于钩子点：before_llm 为请求、after_llm 为响应）
 * @param agentId  Agent ID
 * @param runId    本次运行 ID
 * @param metadata 额外元数据
 */
public record HookContext(
        Object input,
        String agentId,
        String runId,
        Map<String, Object> metadata
) {
    /**
     * 是否命中短路（如自动回复：不调 LLM 直接返回）。
     */
    public boolean shortCircuitRequested() {
        return Boolean.TRUE.equals(metadata.get("short_circuit"));
    }
}