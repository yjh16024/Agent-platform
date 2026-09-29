package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.ConfigFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件配置<b>声明</b>与插件实际读取行为的<b>一致性</b>测试。
 *
 * <h3>为什么这是本组最重要的测试</h3>
 * 声明（给界面看的）和读取（插件自己用的）是两处独立的代码：
 * <pre>
 *   声明：ConfigFieldDef.text("baseUrl", "服务地址")
 *   读取：str(config, CFG_BASE_URL)   // CFG_BASE_URL 必须也是 "baseUrl"
 * </pre>
 * 两者写岔了，表现是<b>「界面上填了、插件却读不到」</b> ——
 * 界面正常显示、保存也成功，唯独功能不生效，是最难查的一类问题
 * （而这正是本次改造要解决的那类问题，不能在改造里重新引入）。
 *
 * <h3>怎么测</h3>
 * 对每个字段造一个"与默认值明显不同的探测值"，塞进 config 让插件解析，
 * 然后要求解析结果的字符串表示<b>发生变化</b>。
 * 用 {@code toString()} 而不是逐个 getter，是因为各插件的配置 record 结构不同 ——
 * 逐个写 getter 既冗长，又会在加字段时忘记同步测试（那正是我们要防的）。
 *
 * <p>反过来也测一条：<b>不认识的键不能影响解析</b> —— 否则历史配置里多一个字段就会让插件崩。</p>
 */
class PluginConfigDeclarationTest {

    /** 探测值：明显不等于任何默认值，若被读到就一定体现在结果里。 */
    private static final String PROBE = "__PROBE__";

    // ---------------------------------------------------------------- TTS

    @Test
    @DisplayName("★ TTS：声明的每个键，插件都真的读到了")
    void ttsDeclarationMatchesParsing() {
        var plugin = new BuiltinTtsPlugin();
        assertFieldsAreEffective(plugin.configFields(),
                cfg -> BuiltinTtsPlugin.TtsConfig.of(cfg).toString());
    }

    @Test
    @DisplayName("TTS：API Key 被标为密钥（界面才会用密码框并预期掩码回显）")
    void ttsApiKeyIsSecret() {
        var f = fieldOf(new BuiltinTtsPlugin().configFields(), "apiKey");
        assertNotNull(f);
        assertTrue(f.secret(), "apiKey 必须是 secret —— 否则密钥会在输入框里明文显示");
        assertTrue(f.required(), "没有 Key 就只能出占位音，应当标必填");
    }

    // ---------------------------------------------------------------- 联网搜索

    @Test
    @DisplayName("★ 联网搜索：声明的每个键，插件都真的读到了")
    void webSearchDeclarationMatchesParsing() {
        var plugin = new BuiltinWebSearchPlugin();
        assertFieldsAreEffective(plugin.configFields(),
                cfg -> BuiltinWebSearchPlugin.SearchConfig.of(cfg).toString());
    }

    @Test
    @DisplayName("联网搜索：不应有任何必填项（不填密钥也能走 DuckDuckGo 兜底）")
    void webSearchHasNoRequiredField() {
        for (ConfigFieldDef f : new BuiltinWebSearchPlugin().configFields()) {
            assertFalse(f.required(), f.key() + " 不该标必填 —— 这个插件零配置可用");
        }
    }

    // ---------------------------------------------------------------- 图片生成

    @Test
    @DisplayName("★ 图片生成：声明的每个键，插件都真的读到了")
    void imageGenDeclarationMatchesParsing() {
        var plugin = new BuiltinImageGenPlugin();
        assertFieldsAreEffective(plugin.configFields(),
                cfg -> BuiltinImageGenPlugin.ImageConfig.of(cfg).toString());
    }

    // ---------------------------------------------------------------- 告警推送

    @Test
    @DisplayName("★ 告警推送：声明的每个键，插件都真的读到了")
    void notifierDeclarationMatchesParsing() {
        var plugin = new BuiltinNotifierPlugin();
        assertFieldsAreEffective(plugin.configFields(),
                cfg -> BuiltinNotifierPlugin.NotifyConfig.of(cfg).toString());
    }

    @Test
    @DisplayName("告警推送：渠道是下拉，选项要覆盖插件支持的全部渠道")
    void notifierChannelsAreSelectable() {
        ConfigFieldDef f = fieldOf(new BuiltinNotifierPlugin().configFields(), "channel");
        assertNotNull(f);
        var values = f.options().stream().map(ConfigFieldDef.Option::value).toList();
        assertTrue(values.contains("feishu"));
        assertTrue(values.contains("dingtalk"));
        assertTrue(values.contains("wecom"));
        assertTrue(values.contains("slack"));
    }

    // ---------------------------------------------------------------- 用量预警

    @Test
    @DisplayName("★ 用量预警：声明的每个键，插件都真的读到了")
    void usageGuardDeclarationMatchesParsing() {
        var plugin = new BuiltinUsageGuardPlugin(null);
        assertFieldsAreEffective(plugin.configFields(),
                cfg -> BuiltinUsageGuardPlugin.GuardConfig.of(cfg).toString());
    }

    // ---------------------------------------------------------------- 无配置的插件

    @Test
    @DisplayName("无状态插件声明空列表（界面会退回自由 JSON，而不是显示空表单）")
    void statelessPluginsDeclareNothing() {
        assertTrue(new BuiltinClockPlugin().configFields().isEmpty());
        assertTrue(new BuiltinBalancePlugin(null).configFields().isEmpty());
    }

    // ---------------------------------------------------------------- 相容性

    @Test
    @DisplayName("★ 未知键不影响解析（历史配置里多出的字段不能让插件崩）")
    void unknownKeysAreIgnored() {
        ObjectNode cfg = JsonNodeFactory.instance.objectNode();
        cfg.put("future_field_from_newer_ui", "x");
        cfg.put("another_one", 123);

        // 都不该抛异常
        BuiltinTtsPlugin.TtsConfig.of(cfg);
        BuiltinWebSearchPlugin.SearchConfig.of(cfg);
        BuiltinImageGenPlugin.ImageConfig.of(cfg);
        BuiltinNotifierPlugin.NotifyConfig.of(cfg);
        BuiltinUsageGuardPlugin.GuardConfig.of(cfg);
    }

    @Test
    @DisplayName("null / 空配置得到与默认值一致的解析结果")
    void nullConfigFallsBackToDefaults() {
        var empty = JsonNodeFactory.instance.objectNode();
        assertTrue(BuiltinTtsPlugin.TtsConfig.of(empty).toString()
                        .equals(BuiltinTtsPlugin.TtsConfig.of(null).toString()),
                "空对象与 null 都应回落到同一套默认值");
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 逐个字段验证"声明 → 解析"这条链路真的通。
     *
     * <p>做法：对每个字段，在默认配置之上只加它一项（值用探测值），
     * 断言解析结果的文本表示与"原样"不同。不同即说明该键被 {@code of()} 读到了。</p>
     */
    private static void assertFieldsAreEffective(List<ConfigFieldDef> fields,
                                                 Function<JsonNode, String> parseToString) {
        assertFalse(fields.isEmpty(), "应当声明了配置项");
        String base = parseToString.apply(null);

        for (ConfigFieldDef f : fields) {
            ObjectNode cfg = JsonNodeFactory.instance.objectNode();
            cfg.set(f.key(), probeFor(f));
            String withField = parseToString.apply(cfg);

            assertFalse(base.equals(withField),
                    "声明的键「" + f.key() + "」没有被插件的 of() 读到 —— "
                            + "界面填了也不会生效（检查声明的 key 与代码里读的常量是否一致）");
        }
    }

    /**
     * 按控件类型给一个能体现差异的探测值。
     *
     * <p>⚠️ 关键在于<b>"这个值必须与默认值不同"</b>：若探到默认值上，解析结果当然不变，
     * 就会被误判成"这个键没被读到"（我第一版取 select 的最后一项，而它恰好是默认的 generic，
     * 于是三个测试红了 —— 测试本身的这个坑值得留在这里）。</p>
     */
    private static JsonNode probeFor(ConfigFieldDef f) {
        return switch (f.kind()) {
            case number -> JsonNodeFactory.instance.numberNode(
                    f.min() != null ? f.min() : 7.5);
            // 开关要探与默认相反的值：取 true 就会撞上默认 true 的字段（如"失败时推送"）
            case bool -> JsonNodeFactory.instance.booleanNode(!"true".equalsIgnoreCase(f.defaultValue()));
            case select -> JsonNodeFactory.instance.textNode(pickNotDefault(f));
            default -> JsonNodeFactory.instance.textNode(PROBE);
        };
    }

    /** 从选项里挑一个与默认值不同的；全等则用探测串。 */
    private static String pickNotDefault(ConfigFieldDef f) {
        if (f.options() == null) {
            return PROBE;
        }
        for (ConfigFieldDef.Option o : f.options()) {
            if (!o.value().equals(f.defaultValue())) {
                return o.value();
            }
        }
        return PROBE;
    }

    private static ConfigFieldDef fieldOf(List<ConfigFieldDef> fields, String key) {
        return fields.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
    }
}
