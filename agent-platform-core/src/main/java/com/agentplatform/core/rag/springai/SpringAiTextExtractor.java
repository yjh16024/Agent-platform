package com.agentplatform.core.rag.springai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI 版文档解析（TikaDocumentReader）。
 * <p>仅在 {@code agent-platform.springai.rag.enabled=true} 时存在；
 * 不存在时摄取管线回退自研 DocumentParser，行为不变。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.springai.rag.enabled", havingValue = "true", matchIfMissing = false)
public class SpringAiTextExtractor {

    /**
     * 从文件字节提取纯文本（支持 txt/md/html/pdf/docx/xlsx 等 Tika 支持格式）。
     */
    public String extract(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        TikaDocumentReader reader = new TikaDocumentReader(resource,
                ExtractedTextFormatter.builder().build());
        List<Document> documents = reader.get();
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        String text = documents.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));
        log.debug("Spring AI Tika extracted {} chars from {}", text.length(), fileName);
        return text;
    }
}
