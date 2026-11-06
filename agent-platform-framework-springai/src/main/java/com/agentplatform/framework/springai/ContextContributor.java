package com.agentplatform.framework.springai;

/**
 * 上下文片段提供者（E3 的注入源）。
 *
 * <p>框架不知道「长期画像 / 早期摘要 / 向量召回」从哪来，也不想知道——
 * 宿主实现这个接口，返回一段要拼进 system prompt 的文本即可；返回空串表示本轮无内容。
 */
@FunctionalInterface
public interface ContextContributor {

    /**
     * @param conversationId 会话 ID（可能为空串）
     * @param userText       本轮用户输入
     * @return 要注入的文本；空 / null 表示不注入
     */
    String contribute(String conversationId, String userText);
}
