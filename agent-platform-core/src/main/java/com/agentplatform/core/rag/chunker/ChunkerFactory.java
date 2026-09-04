package com.agentplatform.core.rag.chunker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 切分器工厂（工厂模式）。
 * <p>Spring 自动收集所有 {@link Chunker} Bean，按策略名装配；未知策略回退递归切分。</p>
 */
@Component
@RequiredArgsConstructor
public class ChunkerFactory {

    private final Map<String, Chunker> chunkers;

    /**
     * 按策略名获取切分器。
     */
    public Chunker get(String strategy) {
        if (strategy == null) {
            return chunkers.get("recursive");
        }
        return chunkers.getOrDefault(strategy, chunkers.get("recursive"));
    }
}