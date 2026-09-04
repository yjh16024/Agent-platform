package com.agentplatform.core.rag.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析器。
 * <p>
 * Markdown / 文本 / HTML 走原生解析，PDF / Docx / PPTx / Excel 等走
 * Apache Tika 统一提取纯文本。
 * </p>
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 解析文档为纯文本。
     *
     * @param fileName 文件名（推断类型）
     * @param content  原始字节流
     * @param fileType 已知文件类型（可空，空则从文件名推断）
     */
    public String parse(String fileName, byte[] content, String fileType) {
        String type = fileType == null ? inferType(fileName) : fileType.toLowerCase();
        return switch (type) {
            case "md", "markdown", "txt", "html", "htm" -> parseText(new String(content, StandardCharsets.UTF_8), type);
            default -> parseWithTika(fileName, content);
        };
    }

    /**
     * 解析富文档（PDF/Docx 等）走 Tika。
     */
    private String parseWithTika(String fileName, byte[] content) {
        try (InputStream in = new java.io.ByteArrayInputStream(content)) {
            return tika.parseToString(in);
        } catch (IOException | TikaException e) {
            log.warn("Tika parse failed for {}, fallback to ISO-8859-1", fileName, e);
            return new String(content, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * 纯文本类文档解析：markdown 去代码块标注，html 去标签。
     */
    private String parseText(String text, String type) {
        return switch (type) {
            case "html", "htm" -> stripHtml(text);
            default -> text;
        };
    }

    /**
     * HTML 去标签（简单实现）。
     */
    private String stripHtml(String html) {
        return html.replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">");
    }

    /**
     * 从文件名推断类型。
     */
    public String inferType(String fileName) {
        if (fileName == null) {
            return "txt";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "txt";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}