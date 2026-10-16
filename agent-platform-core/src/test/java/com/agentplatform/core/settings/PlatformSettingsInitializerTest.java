package com.agentplatform.core.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformSettingsInitializer} 的注入与**优先级**测试（2026-10-15）。
 *
 * <h3>为什么优先级是本功能的地基</h3>
 * 需重启的设置项靠"文件值被注入 Environment、供 @ConditionalOnProperty 在启动装配时读取"生效。
 * 注入位置错一格，行为就全错：
 * <ul>
 *   <li>排到配置文件**之后** → 页面改的值永远被 yml 默认值盖住，"重启生效"是空话；</li>
 *   <li>排到环境变量**之前** → 运维的救命通道（{@code SPRING_AI_ENABLED=false} 这类）失效，
 *       页面写错时无处可退。</li>
 * </ul>
 * 所以下面两条断言必须同时存在 —— 只测"文件值生效"的话，一个 {@code addFirst}
 * （压过一切）的实现也能通过。
 *
 * <p>测试里手动构造 {@code systemEnvironment} 与 {@code application.yml} 两个 PropertySource
 * 来复现真实的源顺序（MockEnvironment 默认只有一个 mockProperties 源）。</p>
 */
class PlatformSettingsInitializerTest {

    @TempDir
    Path tmp;

    private static final String KEY = "agent-platform.springai.enabled";

    private MockEnvironment envWith(Path file, Map<String, Object> environmentLayer) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("agent-platform.settings.file", file.toString());
        // 顺序即优先级：先加 systemEnvironment（高），再加 application.yml（低）
        env.getPropertySources().addLast(new MapPropertySource("systemEnvironment", environmentLayer));
        env.getPropertySources().addLast(new MapPropertySource("application.yml",
                Map.of(KEY, "true")));
        return env;
    }

    @Test
    @DisplayName("文件值应压过 application.yml（否则「重启生效」是空话）")
    void fileValueOverridesConfigData() throws IOException {
        Path file = tmp.resolve("s.properties");
        Files.writeString(file, KEY + "=false\n");
        MockEnvironment env = envWith(file, Map.of());

        PlatformSettingsInitializer.inject(env);

        assertEquals("false", env.getProperty(KEY));
    }

    @Test
    @DisplayName("环境变量应压过文件值（运维的救命通道不能被页面设置挤掉）")
    void environmentVariableStillWins() throws IOException {
        Path file = tmp.resolve("s.properties");
        Files.writeString(file, KEY + "=false\n");
        MockEnvironment env = envWith(file, Map.of(KEY, "true"));

        PlatformSettingsInitializer.inject(env);

        assertEquals("true", env.getProperty(KEY));
    }

    @Test
    @DisplayName("文件不存在：不注入任何源，现状不变")
    void missingFileInjectsNothing() {
        MockEnvironment env = envWith(tmp.resolve("does-not-exist.properties"), Map.of());

        PlatformSettingsInitializer.inject(env);

        assertFalse(env.getPropertySources().contains(PlatformSettingsInitializer.SOURCE_NAME));
        assertEquals("true", env.getProperty(KEY));
    }

    @Test
    @DisplayName("坏文件：不抛异常、不注入（设置为非核心链路，不能拖垮启动）")
    void brokenFileDoesNotCrashStartup() throws IOException {
        Path file = tmp.resolve("s.properties");
        Files.writeString(file, "broken=\\uZZZZ\n");
        MockEnvironment env = envWith(file, Map.of());

        assertDoesNotThrow(() -> PlatformSettingsInitializer.inject(env));

        assertFalse(env.getPropertySources().contains(PlatformSettingsInitializer.SOURCE_NAME));
    }

    @Test
    @DisplayName("注入的源位置：紧跟 systemEnvironment 之后（先于配置文件层）")
    void injectedSourceSitsRightAfterSystemEnvironment() throws IOException {
        Path file = tmp.resolve("s.properties");
        Files.writeString(file, KEY + "=false\n");
        MockEnvironment env = envWith(file, Map.of());

        PlatformSettingsInitializer.inject(env);

        assertTrue(env.getPropertySources().contains(PlatformSettingsInitializer.SOURCE_NAME));
        // 断言相对顺序而不是绝对索引（mockProperties 等源的存在与否不影响结论）
        java.util.List<String> names = new java.util.ArrayList<>();
        env.getPropertySources().forEach(ps -> names.add(ps.getName()));
        int envIdx = names.indexOf("systemEnvironment");
        int fileIdx = names.indexOf(PlatformSettingsInitializer.SOURCE_NAME);
        int ymlIdx = names.indexOf("application.yml");
        assertTrue(envIdx < fileIdx, "注入源应在 systemEnvironment 之后");
        assertTrue(fileIdx < ymlIdx, "注入源应在 application.yml 之前");
    }
}
