package com.agentplatform.core.skill;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill 导入器单元测试（YAML/JSON Manifest 解析）。
 */
class SkillImporterTest {

    private SkillImporter importer;

    @BeforeEach
    void setUp() {
        importer = new SkillImporter();
    }

    @Test
    @DisplayName("YAML Manifest 解析成功")
    void parseYaml() {
        String yaml = """
                name: 客服话术助手
                version: 1.0.0
                description: 提供客服回复话术
                prompt: 你是一名客服，用户问：{{query}}
                tools:
                  - search
                  - calc
                """;
        SkillManifest m = importer.parse(yaml);
        assertEquals("客服话术助手", m.name());
        assertEquals("1.0.0", m.version());
        assertEquals(2, m.tools().size());
    }

    @Test
    @DisplayName("JSON Manifest 解析成功")
    void parseJson() {
        String json = """
                {"name":"json skill","version":"2.0.0","tools":["calc"]}
                """;
        SkillManifest m = importer.parse(json);
        assertEquals("json skill", m.name());
        assertEquals("2.0.0", m.version());
        assertEquals("calc", m.tools().get(0));
    }

    @Test
    @DisplayName("缺失必填字段抛校验异常")
    void missingRequiredField() {
        String yaml = """
                name: 缺少版本
                """;
        BizException ex = assertThrows(BizException.class, () -> importer.parse(yaml));
        assertTrue(ex.getMessage().contains("version"));
    }

    @Test
    @DisplayName("空 Manifest 抛异常")
    void emptyManifest() {
        assertThrows(BizException.class, () -> importer.parse(""));
        assertThrows(BizException.class, () -> importer.parse(null));
    }
}