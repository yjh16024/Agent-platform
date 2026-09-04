package com.agentplatform.core.plugin.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Agent 管线执行结果。
 *
 * @param reply          最终回复文本
 * @param shortCircuited 是否被 Hook 短路（未调 LLM，直接返回预设答复）
 * @param extras         附加产物（如 TTS audio_url）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineResult(String reply, boolean shortCircuited, Map<String, Object> extras) {
}