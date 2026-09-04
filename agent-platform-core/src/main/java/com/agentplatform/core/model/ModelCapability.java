package com.agentplatform.core.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 模型能力标签（多模态 / 工具 / 上下文窗口 / 价格档位）。
 * <p>用于模型路由：按能力标签 + 健康度 + 成本策略选择具体实例。</p>
 */
public enum ModelCapability {
    /** 文本对话 */
    TEXT,
    /** 视觉（图生文） */
    VISION,
    /** 音频 */
    AUDIO,
    /** 工具调用（function calling） */
    TOOL,
    /** 嵌入（embedding） */
    EMBEDDING,
    /** 思维链 / 推理 */
    REASONING;

    /**
     * 便捷构造：命名字符串转能力集。
     */
    public static Set<ModelCapability> of(ModelCapability... caps) {
        Set<ModelCapability> set = caps.length == 0 ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(Set.of(caps));
        return set;
    }
}