package com.agentplatform.core.rag.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档解析器单元测试。
 */
class DocumentParserTest {

    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DocumentParser();
    }

    @Test
    @DisplayName("Markdown 文本原样解析")
    void markdownParse() {
        String md = "# 标题\n\n这是正文内容";
        String result = parser.parse("doc.md", md.getBytes(StandardCharsets.UTF_8), "md");
        assertEquals(md, result);
    }

    @Test
    @DisplayName("HTML 去标签")
    void htmlStripTags() {
        String html = "<html><body><p>Hello &nbsp; World</p></body></html>";
        String result = parser.parse("doc.html", html.getBytes(StandardCharsets.UTF_8), "html");
        assertTrue(result.contains("Hello"));
        assertFalse(result.contains("<p>"));
        assertFalse(result.contains("<html>"));
    }

    @Test
    @DisplayName("从文件名推断类型")
    void inferType() {
        assertEquals("pdf", parser.inferType("report.pdf"));
        assertEquals("md", parser.inferType("notes.md"));
        assertEquals("docx", parser.inferType("word.docx"));
        assertEquals("txt", parser.inferType("noextension"));
    }
}