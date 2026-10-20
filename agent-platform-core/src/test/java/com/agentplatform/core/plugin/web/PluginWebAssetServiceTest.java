package com.agentplatform.core.plugin.web;

import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.PluginRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginWebAssetService} 的测试（2026-10-19，D 档可执行界面）。
 *
 * <h3>为什么这个类必须有测试</h3>
 * 它把 {@code web/} 下的文件按 HTTP 送出去 —— **路径处理错一格就是任意文件读取**。
 * 而这类错误在正常使用中完全看不出来（正常路径永远是对的），只有构造畸形输入才暴露。
 *
 * <p>另一个易错点是"两种来源"：外部插件读 jar、内置插件读 classpath。两者的失败形态不同
 * （jar 不存在 / 条目不存在 / classpath 不存在），必须各测一遍，否则容易出现
 * "外部插件能用、内置插件 404"这种只在发布后才发现的问题。</p>
 */
class PluginWebAssetServiceTest {

    @TempDir
    Path tmp;

    // ---------------- 路径归一化（安全核心） ----------------

    @Test
    @DisplayName("路径归一化：合法路径保留，越界的 `..` 一律拒绝")
    void normalizeHandlesTraversal() {
        // 三个合法输入：归一化后仍在根下
        assertEquals("b.js", PluginWebAssetService.normalize("a/../b.js"), "回到根内的相对路径合法");
        assertEquals("web/pet.js", PluginWebAssetService.normalize("/web/pet.js"), "去掉前导斜杠");
        assertEquals("web/pet.js", PluginWebAssetService.normalize("web\\pet.js"), "反斜杠按分隔符处理");

        // 四个非法输入：归一化后越界
        assertNull(PluginWebAssetService.normalize("../secret.js"));
        assertNull(PluginWebAssetService.normalize("web/../../secret.js"));
        assertNull(PluginWebAssetService.normalize(""), "空路径没有意义");
        assertNull(PluginWebAssetService.normalize(null));
    }

    @Test
    @DisplayName("扩展名白名单：js/css/svg 放行，class/html/exe 一律 null")
    void extensionWhitelist() {
        assertNotNull(PluginWebAssetService.contentTypeOf("js"));
        assertNotNull(PluginWebAssetService.contentTypeOf("css"));
        assertNotNull(PluginWebAssetService.contentTypeOf("svg"));
        assertNull(PluginWebAssetService.contentTypeOf("class"));
        assertNull(PluginWebAssetService.contentTypeOf("html"), "可执行界面是注入的 script，不需要 html 文档");
        assertNull(PluginWebAssetService.contentTypeOf("exe"));

        assertNull(PluginWebAssetService.extensionOf("web/noext"));
        assertNull(PluginWebAssetService.contentTypeOf("hidden"), "隐藏文件的后缀不在白名单");
        assertEquals("js", PluginWebAssetService.extensionOf("web/a.b/c.JS"));
    }

    // ---------------- 两种来源 ----------------

    @Test
    @DisplayName("内置插件：从 classpath 的 plugin-web/<id>/ 读取")
    void readsClasspathAsset() {
        PluginWebAssetService service = serviceFor(def("demo", null));

        PluginWebAssetService.WebAsset asset = service.load("default", "demo", "hello.js");

        assertNotNull(asset);
        assertTrue(asset.contentType().startsWith("text/javascript"));
        assertTrue(new String(asset.bytes(), StandardCharsets.UTF_8).contains("__apPlugin__"));
    }

    @Test
    @DisplayName("外部插件：从 artifact_uri 指向的 jar 里读 web/ 条目")
    void readsJarAsset() throws IOException {
        Path jar = makeJar("web/app.js", "console.log('from-jar');");
        PluginWebAssetService service = serviceFor(def("ext_demo", jar.toUri().toString()));

        PluginWebAssetService.WebAsset asset = service.load("default", "ext_demo", "app.js");

        assertNotNull(asset);
        assertEquals("console.log('from-jar');", new String(asset.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("文件不存在 / 插件不存在 / 非法 pluginId：一律 null（控制器统一 404）")
    void missingThingsReturnNull() throws IOException {
        PluginWebAssetService known = serviceFor(def("demo", null));
        assertNull(known.load("default", "demo", "nope.js"), "classpath 里没有这个文件");
        assertNull(known.load("default", "demo", "a/../../b.js"), "越界路径");

        PluginWebAssetService unknown = serviceFor(null);
        assertNull(unknown.load("default", "ghost", "hello.js"), "插件不存在");

        assertNull(known.load("default", "../evil", "hello.js"), "pluginId 只允许安全字符");
        assertNull(known.load("default", "demo", "payload.exe"), "扩展名不在白名单");
    }

    // ---------------- 辅助 ----------------

    private static PluginDef def(String pluginId, String artifactUri) {
        return PluginDef.builder()
                .pluginId(pluginId)
                .tenantId("__platform__")
                .name(pluginId)
                .artifactUri(artifactUri)
                .build();
    }

    private static PluginWebAssetService serviceFor(PluginDef def) {
        PluginRepository repo = Mockito.mock(PluginRepository.class);
        Mockito.when(repo.findByTenantIdAndPluginId(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(def == null ? Optional.empty() : Optional.of(def));
        return new PluginWebAssetService(repo);
    }

    private Path makeJar(String entryName, String content) throws IOException {
        Path jar = tmp.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
