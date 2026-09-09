package com.agentplatform.core.rag.springai;

import com.agentplatform.core.rag.chunker.ChunkSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 版文档切分（TokenTextSplitter）。
 * <p>仅在 {@code agent-platform.springai.rag.enabled=true} 时存在；
 * 不存在时摄取管线回退自研 Chunker（recursive/semantic/structural），行为不变。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.springai.rag.enabled", havingValue = "true", matchIfMissing = false)
public class SpringAiChunker {

    /**
     * 切分文本为 {@link ChunkSegment} 列表（seqNo 从 0 编号）。
     *
     * @param text         清洗后的文本
     * @param chunkSize    目标 token 数（TokenTextSplitter 按 token 切分，近似字符切分）
     */
    public List<ChunkSegment> split(String text, int chunkSize) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize <= 0 ? 512 : chunkSize)
                .build();
        List<Document> documents = splitter.split(new Document(text == null ? "" : text));
        List<ChunkSegment> segments = new ArrayList<>();
        int seqNo = 0;
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            String content = doc.getText();
            if (content == null || content.isBlank()) {
                continue;
            }
            segments.add(ChunkSegment.of(seqNo++, content.trim()));
        }
        log.debug("Spring AI TokenTextSplitter: {} chars -> {} segments (chunkSize={})",
                text == null ? 0 : text.length(), segments.size(), chunkSize);
        return segments;
    }
}
