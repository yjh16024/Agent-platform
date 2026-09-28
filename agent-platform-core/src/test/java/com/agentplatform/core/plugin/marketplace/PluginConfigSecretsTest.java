package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.core.model.secret.ModelKeyCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginConfigSecrets} 的测试。
 *
 * <h3>为什么这组测试值得写细</h3>
 * 它守着两件<b>后果严重但表现隐蔽</b>的事：
 * <ol>
 *   <li><b>密钥必须以密文落库</b>。若哪天有人重构时把它绕过了，功能一切正常 ——
 *       直到某次备份或日志把用户的付费密钥泄出去。没有任何症状会提醒你。</li>
 *   <li><b>掩码不能当真密钥存下来</b>。前端回显 {@code sk-***abcd}，用户不改那一栏直接保存时
 *       提交回来的就是这串掩码。若被存下，之后每次调用都 401，<b>而界面上仍显示"已配置"</b> ——
 *       用户会以为是平台坏了。</li>
 * </ol>
 */
class PluginConfigSecretsTest {

    private static final String MASTER_KEY = "unit-test-master-key-please-change";
    private static final String REAL_KEY = "sk-realkey-1234567890abcdef";

    private final PluginConfigSecrets secrets =
            new PluginConfigSecrets(new ModelKeyCrypto(MASTER_KEY));

    // ---------------------------------------------------------------- 加密落库

    @Test
    @DisplayName("★ 密钥以密文落库：库里拿不到明文，且带 enc:v1: 前缀")
    void secretIsEncryptedAtRest() {
        Map<String, Object> sealed = secrets.seal(Map.of("apiKey", REAL_KEY), null);

        String stored = String.valueOf(sealed.get("apiKey"));
        assertTrue(stored.startsWith("enc:v1:"), "必须带前缀以便与历史明文区分，实际：" + stored);
        assertFalse(stored.contains(REAL_KEY), "库里绝不能出现明文密钥");
    }

    @Test
    @DisplayName("非密钥字段原样保存（音色、开关、数值都没必要加密，加密反而看不清）")
    void nonSecretFieldsStayReadable() {
        Map<String, Object> sealed = secrets.seal(new LinkedHashMap<>(Map.of(
                "apiKey", REAL_KEY,
                "voice", "FunAudioLLM/CosyVoice2-0.5B:alex",
                "maxChars", 150,
                "timeoutMs", 8000)), null);

        assertEquals("FunAudioLLM/CosyVoice2-0.5B:alex", sealed.get("voice"));
        assertEquals(150, sealed.get("maxChars"));
        assertEquals(8000, sealed.get("timeoutMs"));
    }

    @Test
    @DisplayName("★ maxTokens / maxChars 这类含 token 字样的普通项不能被误加密（误伤会让插件拿到密文）")
    void tokenLikeButHarmlessKeysAreNotTreatedAsSecrets() {
        assertFalse(PluginConfigSecrets.isSecretKey("maxTokens"), "maxTokens 是数值上限，不是密钥");
        assertFalse(PluginConfigSecrets.isSecretKey("maxChars"));
        assertFalse(PluginConfigSecrets.isSecretKey("tokenLimit"));
        assertFalse(PluginConfigSecrets.isSecretKey("voice"));

        assertTrue(PluginConfigSecrets.isSecretKey("apiKey"));
        assertTrue(PluginConfigSecrets.isSecretKey("api_key"), "下划线写法也要认");
        assertTrue(PluginConfigSecrets.isSecretKey("API-KEY"), "大小写与连字符都要认");
        assertTrue(PluginConfigSecrets.isSecretKey("accessToken"));
        assertTrue(PluginConfigSecrets.isSecretKey("appSecret"));
        assertTrue(PluginConfigSecrets.isSecretKey("password"));
    }

    // ---------------------------------------------------------------- 解密取用

    @Test
    @DisplayName("解密后插件拿到的是真实明文密钥")
    void revealReturnsPlaintext() {
        Map<String, Object> sealed = secrets.seal(Map.of("apiKey", REAL_KEY), null);

        Map<String, Object> plain = secrets.reveal(sealed);

        assertEquals(REAL_KEY, plain.get("apiKey"));
    }

    @Test
    @DisplayName("★ 本机制上线前的历史明文配置仍可用（绝不能因为「解密失败」让老配置挂掉）")
    void legacyPlaintextStillWorks() {
        Map<String, Object> legacy = Map.of("apiKey", "sk-legacy-plain-key", "voice", "alex");

        Map<String, Object> plain = secrets.reveal(legacy);

        assertEquals("sk-legacy-plain-key", plain.get("apiKey"), "无前缀 = 历史明文，原样返回");
        assertEquals("alex", plain.get("voice"));
    }

    @Test
    @DisplayName("主密钥不匹配导致解密失败时：原样传递而不是抛异常（不拖垮整台智能体）")
    void undecryptableValueDoesNotThrow() {
        Map<String, Object> sealed = secrets.seal(Map.of("apiKey", REAL_KEY), null);
        // 换一个主密钥来解 —— 模拟主密钥被换过
        PluginConfigSecrets other = new PluginConfigSecrets(new ModelKeyCrypto("a-completely-different-key"));

        Map<String, Object> plain = other.reveal(sealed);

        assertTrue(String.valueOf(plain.get("apiKey")).startsWith("enc:v1:"),
                "解不开就原样给出：插件侧会表现为调用失败，但不该在这里抛异常");
    }

    // ---------------------------------------------------------------- 掩码回显

    @Test
    @DisplayName("★ 回显给前端的是掩码，绝不是明文（密钥字段永远不出现在接口响应里）")
    void maskNeverLeaksPlaintext() {
        Map<String, Object> sealed = secrets.seal(
                new LinkedHashMap<>(Map.of("apiKey", REAL_KEY, "voice", "alex")), null);

        Map<String, Object> masked = secrets.mask(sealed);

        String shown = String.valueOf(masked.get("apiKey"));
        assertFalse(shown.contains(REAL_KEY), "响应里绝不能出现明文");
        assertTrue(shown.contains("***"), "应形如 sk-***abcd，实际：" + shown);
        assertEquals("alex", masked.get("voice"), "非密钥字段照常回显，用户要看到自己填过的音色");
    }

    @Test
    @DisplayName("未配置密钥时回显空串（前端据此显示「未配置」，而不是显示一团掩码）")
    void missingSecretShowsEmpty() {
        Map<String, Object> masked = secrets.mask(Map.of("voice", "alex"));
        assertFalse(masked.containsKey("apiKey"));
    }

    // ---------------------------------------------------------------- ★ 掩码回传

    @Test
    @DisplayName("★★ 用户没改密钥栏（提交回掩码）时保留原密钥 —— 这是最容易埋下的隐形故障")
    void submittingMaskedValueKeepsExistingSecret() {
        Map<String, Object> existing = secrets.seal(Map.of("apiKey", REAL_KEY), null);
        String maskedShownToUser = String.valueOf(secrets.mask(existing).get("apiKey"));

        // 用户只改了音色，密钥栏保持回显的掩码原样提交
        Map<String, Object> resubmitted = new LinkedHashMap<>();
        resubmitted.put("apiKey", maskedShownToUser);
        resubmitted.put("voice", "new-voice");
        Map<String, Object> sealed = secrets.seal(resubmitted, existing);

        assertNotEquals(maskedShownToUser, sealed.get("apiKey"), "掩码绝不能被当成密钥存下来");
        assertEquals(REAL_KEY, secrets.reveal(sealed).get("apiKey"), "原密钥必须完好保留");
        assertEquals("new-voice", sealed.get("voice"), "同时改动的其它字段要生效");
    }

    @Test
    @DisplayName("★ 密钥栏留空（用户明确清空）时保留原值，与模型侧的约定一致")
    void blankValueKeepsExistingSecret() {
        Map<String, Object> existing = secrets.seal(Map.of("apiKey", REAL_KEY), null);

        Map<String, Object> sealed = secrets.seal(new LinkedHashMap<>(Map.of("apiKey", "")), existing);

        assertEquals(REAL_KEY, secrets.reveal(sealed).get("apiKey"));
    }

    @Test
    @DisplayName("填了新密钥则覆盖旧值（换服务商/换账号的常规操作）")
    void newValueOverwritesExistingSecret() {
        Map<String, Object> existing = secrets.seal(Map.of("apiKey", "sk-old-key-0000000000"), null);
        String fresh = "sk-brand-new-key-9999999999";

        Map<String, Object> sealed = secrets.seal(Map.of("apiKey", fresh), existing);

        assertEquals(fresh, secrets.reveal(sealed).get("apiKey"));
    }

    @Test
    @DisplayName("首次挂载且提交掩码：没有可沿用的原值，丢弃而不是存下掩码")
    void maskedValueWithoutExistingIsDropped() {
        Map<String, Object> sealed = secrets.seal(
                new LinkedHashMap<>(Map.of("apiKey", "sk-***abcd", "voice", "alex")), null);

        assertNull(sealed.get("apiKey"), "宁可视为「未配置」，也不能存下一个假密钥");
        assertEquals("alex", sealed.get("voice"));
    }

    // ---------------------------------------------------------------- 边界

    @Test
    @DisplayName("空配置与 null 配置都不抛异常")
    void emptyConfigsAreSafe() {
        assertTrue(secrets.seal(null, null).isEmpty());
        assertTrue(secrets.seal(Map.of(), null).isEmpty());
        assertTrue(secrets.reveal(null).isEmpty());
        assertTrue(secrets.reveal(Map.of()).isEmpty());
        assertTrue(secrets.mask(null).isEmpty());
        assertTrue(secrets.mask(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("两次加密同一明文得到不同密文（随机 IV：防止从密文比对推断出「两台智能体用了同一个 Key」）")
    void encryptionIsRandomized() {
        String a = String.valueOf(secrets.seal(Map.of("apiKey", REAL_KEY), null).get("apiKey"));
        String b = String.valueOf(secrets.seal(Map.of("apiKey", REAL_KEY), null).get("apiKey"));

        assertNotEquals(a, b, "相同明文应产生不同密文");
        assertEquals(REAL_KEY, secrets.reveal(Map.of("apiKey", a)).get("apiKey"));
        assertEquals(REAL_KEY, secrets.reveal(Map.of("apiKey", b)).get("apiKey"));
    }
}
