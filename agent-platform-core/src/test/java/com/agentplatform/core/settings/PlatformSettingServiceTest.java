package com.agentplatform.core.settings;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingGroup;
import com.agentplatform.core.settings.PlatformSettingDtos.SettingItem;
import com.agentplatform.core.settings.PlatformSettingDtos.UpdateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformSettingService} 的取值与写入测试（2026-10-15）。
 *
 * <h3>为什么重点测这几类</h3>
 * <ul>
 *   <li><b>两层来源的合成</b>（覆盖文件 ?? 基础层 ?? fallback）—— 这是本功能的全部语义，
 *       合成错的表现是"恢复默认没反应""页面值被配置文件盖住"，都是静默的；</li>
 *   <li><b>跳过 platformSettings 注入源</b> —— 启动注入把文件值并进了 Environment，
 *       服务读"基础层"时必须显式跳过它，否则"当前值"其实是自己写过的值（自证循环），
 *       "恢复默认"将永远显示旧值；</li>
 *   <li><b>restartRequiredKeys 只在"值真的变了"时返回</b> —— 每次都返回会让用户
 *       重复保存同一项时也被弹窗劝去重启；</li>
 *   <li><b>写入校验</b> —— 脏值一旦落盘会被各读点直接消费，错误会以"开关时灵时不灵"扩散。</li>
 * </ul>
 */
class PlatformSettingServiceTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("platform-settings.properties");
    }

    private PlatformSettingService service(MockEnvironment env) {
        return new PlatformSettingService(env, file().toString());
    }

    private static SettingItem itemOf(PlatformSettingDtos.SettingsView view, String key) {
        for (SettingGroup g : view.groups()) {
            for (SettingItem it : g.items()) {
                if (it.key().equals(key)) {
                    return it;
                }
            }
        }
        throw new AssertionError("视图里找不到设置项：" + key);
    }

    // ---------------- 两层来源的合成 ----------------

    @Test
    @DisplayName("无覆盖文件时：读到的是环境（基础层）的值")
    void fallsBackToEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.AUDIT_ENABLED, "false");

        PlatformSettingService svc = service(env);

        assertFalse(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, true));
    }

    @Test
    @DisplayName("环境里也没有时：raw 为 null，读取回落调用方 fallback")
    void fallsBackToCallerFallback() {
        PlatformSettingService svc = service(new MockEnvironment());

        assertNull(svc.raw(PlatformSettingRegistry.AUDIT_ENABLED));
        assertTrue(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, true));
        assertEquals(600L, svc.longValue(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, 600L));
        assertEquals(List.of(), svc.list(PlatformSettingRegistry.TOOL_DISABLED));
    }

    @Test
    @DisplayName("写入立即生效（热切换）并落盘；文件里只含被改过的项")
    void applyTakesEffectImmediatelyAndPersistsOnlyOverrides() throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED, "false");

        PlatformSettingService svc = service(env);
        svc.apply(Map.of(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED, "true"));

        assertTrue(svc.bool(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED, false));
        String text = Files.readString(file());
        assertTrue(text.contains("agent-platform.memory.auto-profile.enabled=true"), "覆盖行应写入文件");
        // 没改过的项**不写进文件** —— 复制全量会让"改 yml 默认值"与"文件里的旧默认"悄悄分叉
        assertFalse(text.contains(PlatformSettingRegistry.AUDIT_ENABLED));
    }

    @Test
    @DisplayName("恢复默认：删掉覆盖后回落到环境（基础层）的值")
    void resetRestoresBaseValue() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.AUDIT_ENABLED, "false");
        PlatformSettingService svc = service(env);

        svc.apply(Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "true"));
        assertTrue(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, false));

        svc.reset(List.of(PlatformSettingRegistry.AUDIT_ENABLED));
        assertFalse(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, true));
    }

    @Test
    @DisplayName("基础层的占位符原文会被解析（application.yml 里就是 ${X:false} 的写法）")
    void baseResolvesPlaceholderText() {
        MockEnvironment env = new MockEnvironment();
        // 配置文件源里存的是**未解析的原文**，解析占位符是读取方的职责
        env.getPropertySources().addLast(new MapPropertySource("application.yml",
                Map.of(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED, "${SOME_UNSET_VAR:false}")));

        PlatformSettingService svc = service(env);

        assertEquals("false", svc.raw(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED));
        assertFalse(svc.bool(PlatformSettingRegistry.MEMORY_AUTO_PROFILE_ENABLED, true));
    }

    @Test
    @DisplayName("读「基础层」必须跳过 configurationProperties 投影源（真实启动里它排在首位）")
    void baseSkipsConfigurationPropertiesProjection() {
        MockEnvironment env = new MockEnvironment();
        // 真实启动中 Spring Boot 会把 configurationProperties（所有源的合并投影）插到最前面，
        // 它会把 platformSettings 注入层的值也暴露出来 —— 只跳过注入层是不够的。
        env.getPropertySources().addFirst(new MapPropertySource(
                "configurationProperties", Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "true")));
        env.getPropertySources().addLast(new MapPropertySource(
                "application.yml", Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "false")));

        PlatformSettingService svc = service(env);

        assertFalse(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, true),
                "投影源里的 true 来自页面覆盖，读基础层时应穿透它到 application.yml 的 false");
    }

    @Test
    @DisplayName("读「基础层」必须跳过 platformSettings 注入源（否则恢复默认后仍显示旧值）")
    void baseSkipsPlatformSettingsSource() {
        MockEnvironment env = new MockEnvironment();
        // 模拟启动注入：文件值被并进 Environment（高优先级）
        env.getPropertySources().addFirst(new MapPropertySource(
                PlatformSettingsInitializer.SOURCE_NAME, Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "true")));
        // 配置文件层（低优先级）
        env.getPropertySources().addLast(new MapPropertySource(
                "application.yml", Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "false")));

        PlatformSettingService svc = service(env);

        assertFalse(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, true),
                "读到的应是配置文件层的 false，而不是注入源的 true");
    }

    // ---------------- 视图 ----------------

    @Test
    @DisplayName("视图按注册表顺序分组，并如实标记 overridden / 值")
    void viewGroupsInRegistryOrder() {
        PlatformSettingService svc = service(new MockEnvironment());

        PlatformSettingDtos.SettingsView view = svc.view();

        // 「系统提示词」组是 2026-10-20 新增的（输出格式指引可在设置里改）
        assertEquals(List.of("长期记忆", "文件与工具", "操作审计", "模型、存储与事件", "系统提示词"),
                view.groups().stream().map(SettingGroup::name).toList());
        assertEquals("server", view.runtimeMode());

        svc.apply(Map.of(PlatformSettingRegistry.MEMORY_VECTOR_ENABLED, "false"));
        SettingItem changed = itemOf(svc.view(), PlatformSettingRegistry.MEMORY_VECTOR_ENABLED);
        assertTrue(changed.overridden());
        assertEquals("false", changed.value());
        assertFalse(changed.restartRequired());

        SettingItem untouched = itemOf(svc.view(), PlatformSettingRegistry.SPRING_AI_ENABLED);
        assertFalse(untouched.overridden());
        assertTrue(untouched.restartRequired());
    }

    // ---------------- 需重启判定 ----------------

    @Test
    @DisplayName("restartRequiredKeys：只有「值真的变化 且 标了需重启」的项才返回")
    void restartKeysOnlyForActuallyChangedRestartRequiredItems() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.SPRING_AI_ENABLED, "true");
        PlatformSettingService svc = service(env);

        UpdateResult first = svc.apply(Map.of(PlatformSettingRegistry.SPRING_AI_ENABLED, "false"));
        assertEquals(List.of(PlatformSettingRegistry.SPRING_AI_ENABLED), first.restartRequiredKeys());

        // 值没变：不重复劝用户重启
        UpdateResult again = svc.apply(Map.of(PlatformSettingRegistry.SPRING_AI_ENABLED, "false"));
        assertTrue(again.restartRequiredKeys().isEmpty());

        // 热切换项：不进 restartRequiredKeys
        UpdateResult hot = svc.apply(Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "false"));
        assertTrue(hot.restartRequiredKeys().isEmpty());
    }

    // ---------------- 校验 ----------------

    @Test
    @DisplayName("未知键被拒绝（页面过期 / 手写请求）")
    void rejectsUnknownKey() {
        PlatformSettingService svc = service(new MockEnvironment());
        BizException ex = assertThrows(BizException.class,
                () -> svc.apply(Map.of("agent-platform.not-a-setting", "x")));
        assertTrue(ex.getMessage().contains("未知设置项"));
    }

    @Test
    @DisplayName("类型校验：bool / number / enum 的脏值被拒绝，且不落盘")
    void rejectsBadTypedValues() {
        PlatformSettingService svc = service(new MockEnvironment());

        assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.AUDIT_ENABLED, "yes")));
        assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, "abc")));
        assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, "-1")));
        assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.STORAGE_TYPE, "s3")));
        // 含换行的值会写坏配置文件
        assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.TOOL_DISABLED, "calc\nsearch")));

        assertFalse(Files.exists(file()), "校验失败不应产生覆盖文件");
    }

    @Test
    @DisplayName("坏文件视为无覆盖：不抛、回落默认值")
    void brokenFileTreatedAsNoOverride() throws IOException {
        Files.writeString(file(), "agent-platform.audit.enabled=\\uZZZZ\n");   // 非法 Unicode 转义
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.AUDIT_ENABLED, "true");

        PlatformSettingService svc = assertDoesNotThrow(() -> service(env));

        assertTrue(svc.bool(PlatformSettingRegistry.AUDIT_ENABLED, false));
    }

    // ---------------- 桌面（embedded）锁定 ----------------

    @Test
    @DisplayName("内置库模式：Kafka 事件总线被锁定（拒绝写入 + 视图标记 locked）")
    void embeddedLocksEventsToggle() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("embedded");
        PlatformSettingService svc = service(env);

        BizException ex = assertThrows(BizException.class,
                () -> svc.apply(Map.of(PlatformSettingRegistry.EVENTS_ENABLED, "true")));
        assertTrue(ex.getMessage().contains("锁定"));

        SettingItem item = itemOf(svc.view(), PlatformSettingRegistry.EVENTS_ENABLED);
        assertTrue(item.locked());
        assertNotNull(item.lockReason());
        assertEquals("embedded", svc.view().runtimeMode());
    }

    @Test
    @DisplayName("服务端模式：Kafka 事件总线可正常切换")
    void serverModeAllowsEventsToggle() {
        PlatformSettingService svc = service(new MockEnvironment());

        UpdateResult r = svc.apply(Map.of(PlatformSettingRegistry.EVENTS_ENABLED, "false"));

        assertEquals(List.of(PlatformSettingRegistry.EVENTS_ENABLED), r.restartRequiredKeys());
        assertFalse(itemOf(r.view(), PlatformSettingRegistry.EVENTS_ENABLED).locked());
    }

    // ---------------- 数值与清单读取 ----------------

    @Test
    @DisplayName("number / list 的读取与写入")
    void numberAndListValues() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PlatformSettingRegistry.TOOL_DISABLED, "calc, web_search");
        PlatformSettingService svc = service(env);

        assertEquals(List.of("calc", "web_search"), svc.list(PlatformSettingRegistry.TOOL_DISABLED));

        svc.apply(Map.of(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, "120"));
        assertEquals(120L, svc.longValue(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, 600L));

        svc.apply(Map.of(PlatformSettingRegistry.TOOL_DISABLED, ""));
        assertEquals(List.of(), svc.list(PlatformSettingRegistry.TOOL_DISABLED));
    }
}
