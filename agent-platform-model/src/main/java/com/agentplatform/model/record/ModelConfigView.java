package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 平台级模型配置的对外视图（API 响应用）。
 * <p>仅含掩码视图，绝不回传明文 apiKey。</p>
 *
 * @param embedding 嵌入模型绑定（RAG 向量化）
 * @param chat      平台默认对话模型绑定（智能体未单独配置时回退）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelConfigView(ModelBindingView embedding, ModelBindingView chat) {
}
