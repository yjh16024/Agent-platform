package com.agentplatform.core.plugin.web;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginWebService} 的测试（2026-10-19，界面插件安装）。
 *
 * <h3>重点在两件"看不出来"的事</h3>
 * <ol>
 *   <li><b>zip slip</b>：{@code ../evil.js} 这种条目会被普通解压写到插件目录之外 ——
 *       而它**安装成功、界面正常**，只是机器上多了一个文件。必须靠测试钉住；</li>
 *   <li><b>安装半成品</b>：manifest 缺少 entry 指向的文件时，如果先落盘再校验，
 *       会留下一个"看起来装上了、加载时 404"的插件。这里验证的是"先解到临时目录、校验通过才改名"。</li>
 * </ol>
 */
class PluginWebServiceTest {

    @TempDir
    Path tmp;

    private PluginWebService service;

    @BeforeEach
    void setUp() {
        service = new PluginWebService(tmp.toString(), true);
    }

    @Test
    @DisplayName("安装 → 出现在列表与槽位贡献里；file 能读到内容")
    void installsAndContributes() throws IOException {
        service.install(zip(Map.of(
                "manifest.json", MAP_MANIFEST,
                "main.js", "window.__apPlugin__.register('usage_widget', { apply: function(){} });",
                "style.css", ".widget{position:fixed}"
        )));

        List<Map<String, Object>> list = service.list();
        assertEquals(1, list.size());
        assertEquals("usage_widget", list.get(0).get("id"));
        assertEquals(true, list.get(0).get("enabled"), "新装的插件默认启用（装上就能用）");

        List<Map<String, Object>> contribs = service.contributions();
        assertEquals(1, contribs.size());
        Map<String, Object> c = contribs.get(0);
        assertEquals("script", c.get("type"));
        assertEquals("global.overlay", c.get("slot"));
        assertEquals("main.js", c.get("script"));
        assertEquals("style.css", c.get("style"));
        assertEquals(false, c.get("isolate"), "manifest 里显式 false 必须带出");
        // ★ 资源前缀与 jar 插件不同；缺了它前端会按 /plugins/{id}/web/ 拼 URL → 404
        assertEquals("plugin-web", c.get("assetBase"));

        assertTrue(service.file("usage_widget", "main.js").isPresent());
    }

    @Test
    @DisplayName("停用后：列表里 enabled=false，槽位贡献里不再出现（热切换）")
    void disableHidesContribution() throws IOException {
        service.install(zip(Map.of("manifest.json", MAP_MANIFEST, "main.js", "x", "style.css", ".x{}")));

        service.setEnabled("usage_widget", false);

        assertEquals(0, service.contributions().size());
        assertEquals(false, service.list().get(0).get("enabled"));
    }

    @Test
    @DisplayName("zip slip：含 `..` 段的条目直接拒绝（不是归一化后接受）")
    void rejectsZipSlip() {
        BizException ex = assertThrows(BizException.class, () -> service.install(zip(Map.of(
                "manifest.json", MAP_MANIFEST,
                "main.js", "x",
                "../evil.js", "pwn"
        ))));
        assertTrue(ex.getMessage().contains("非法路径"));
        assertTrue(service.list().isEmpty(), "失败的安装不能留下任何目录");
        assertFalse(tmp.resolve("evil.js").toFile().exists(), "绝不能写到插件目录之外");
    }

    @Test
    @DisplayName("缺 manifest / entry 指向的文件不存在：拒绝安装")
    void rejectsIncompletePackages() {
        assertThrows(BizException.class, () -> service.install(zip(Map.of("main.js", "x"))));

        String badEntry = MAP_MANIFEST.replace("main.js", "missing.js");
        assertThrows(BizException.class, () -> service.install(zip(Map.of(
                "manifest.json", badEntry, "main.js", "x"
        ))));
        assertTrue(service.list().isEmpty());
    }

    @Test
    @DisplayName("file：路径穿越 / 非白名单扩展名一律拿不到")
    void fileRejectsTraversal() throws IOException {
        service.install(zip(Map.of("manifest.json", MAP_MANIFEST, "main.js", "x", "style.css", ".x{}")));

        assertTrue(service.file("usage_widget", "../../state.json").isEmpty());
        assertTrue(service.file("usage_widget", "main.exe").isEmpty());
        assertTrue(service.file("../usage_widget", "main.js").isEmpty());
        assertNotNull(service.file("usage_widget", "main.js").orElse(null));
    }

    // ---------------- 辅助 ----------------

    private static final String MAP_MANIFEST = """
            {"id":"usage_widget","name":"用量挂件","version":"1.0.0",
             "slot":"global.overlay","entry":"main.js","style":"style.css",
             "isolate":false,"order":20,"label":"用量挂件"}
            """;

    private static MockMultipartFile zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "plugin.zip", "application/zip", bos.toByteArray());
    }
}
