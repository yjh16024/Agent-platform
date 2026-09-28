package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.core.model.secret.ModelKeyCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件配置里**密钥字段**的加密、解密与掩码。
 *
 * <h3>为什么需要这个类</h3>
 * 插件配置存在 {@code agent_plugin.config}（明文 JSON 列）。绝大多数配置项是无所谓的
 * （音色、开关、规则表），但 TTS 这类插件需要用户填<b>自己的、真实的、要计费的 API Key</b>。
 * 把它明文落库意味着：任何能读这张表的人（DBA、备份文件、日志、截图）都能直接盗用。
 * 模型侧的密钥早就走了「AES-GCM 加密 + 只回掩码」，插件侧不这么做就是<b>明显的倒退</b>。
 *
 * <p>所以三类变换集中在这里，避免"某条路径忘了转换"这种极难排查的问题 ——
 * 那种 bug 的表现是<b>「配置填好了，但插件用不了」</b>，而所有中间环节看起来都是对的：</p>
 * <ol>
 *   <li>{@link #seal}：<b>存</b>之前 —— 加密敏感字段；</li>
 *   <li>{@link #reveal}：<b>用</b>之前 —— 解密后交给插件；</li>
 *   <li>{@link #mask}：<b>回显</b>给前端 —— 只给掩码，绝不给明文。</li>
 * </ol>
 *
 * <h3>密文格式带前缀：{@code enc:v1:...}</h3>
 * 加密的字段会带上 {@code enc:v1:} 前缀，理由有两个：
 * <ul>
 *   <li><b>能识别历史数据</b>。本机制上线前保存的配置是明文，必须原样可用 ——
 *       不能因为"解密失败"就让老用户的插件挂掉。有前缀就能明确区分，而不是靠
 *       试错解密（那会把"明文恰好长得像密文"变成偶发故障）。</li>
 *   <li><b>将来换算法可平滑升级</b>（改版本号即可）。</li>
 * </ul>
 *
 * <h3>★ 掩码回传必须被识别，否则会把掩码当密钥存进去</h3>
 * 前端回显的是 {@code sk-***abcd}。<b>用户不改这一栏直接保存时，提交回来的就是这串掩码</b>。
 * 若不处理，密钥会被替换成字面量 {@code sk-***abcd} —— 之后每次调用都 401，
 * 而用户在界面上看到的仍是"已配置"。{@link #seal} 因此专门识别掩码形态并保留原值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginConfigSecrets {

    /** 密文前缀。带版本号，便于将来更换算法时区分。 */
    private static final String ENC_PREFIX = "enc:v1:";

    /** 掩码里固定出现的标记串（见 {@link ModelKeyCrypto#mask}）。 */
    private static final String MASK_TOKEN = "***";

    private final ModelKeyCrypto crypto;

    /**
     * 落库前的处理：保留原密钥（当提交的是掩码或空值时）→ 加密敏感字段。
     *
     * @param incoming 前端提交的配置（可能含掩码、空值或新密钥）
     * @param existing 库里已有的配置（可能为 null）
     * @return 可直接落库的配置（敏感字段为密文）
     */
    public Map<String, Object> seal(Map<String, Object> incoming, Map<String, Object> existing) {
        if (incoming == null || incoming.isEmpty()) {
            // 明确提交空配置：保留不了任何东西，但也不该把密钥悄悄留下（用户可能就是想清掉）
            return incoming == null ? Map.of() : new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!isSecretKey(key)) {
                out.put(key, value);
                continue;
            }
            // ---- 敏感字段 ----
            String text = value == null ? null : String.valueOf(value);
            if (text == null || text.isBlank()) {
                // 留空 = 沿用原值（与模型侧一致：用户不必为了改音色而重填密钥）
                Object previous = existing == null ? null : existing.get(key);
                if (previous != null) {
                    out.put(key, previous);
                }
                continue;
            }
            if (looksMasked(text)) {
                // 提交回来的是掩码（用户没改这一栏）→ 保留库里那份，绝不能把掩码当密钥存下来
                Object previous = existing == null ? null : existing.get(key);
                if (previous != null) {
                    out.put(key, previous);
                    log.debug("[plugin-config] 字段 {} 提交的是掩码，沿用已保存的值", key);
                }
                continue;
            }
            out.put(key, wrap(crypto.encrypt(text)));
        }
        return out;
    }

    /**
     * 取用前的处理：把敏感字段解密成明文，供插件使用。
     *
     * <p>历史上（本机制上线前）存的是明文，此时无前缀 → 原样返回；
     * 密文解密失败也<b>降级为原样返回</b>而不抛异常 —— 密钥不可用不该让整台智能体起不来，
     * 插件那边会自然表现为"调用失败"，日志里另有记录。</p>
     */
    public Map<String, Object> reveal(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : stored.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!isSecretKey(key) || !(value instanceof CharSequence cs)) {
                out.put(key, value);
                continue;
            }
            String text = cs.toString();
            if (!isWrapped(text)) {
                out.put(key, text);   // 历史明文：原样可用
                continue;
            }
            try {
                out.put(key, crypto.decrypt(unwrap(text)));
            } catch (Exception ex) {
                log.warn("[plugin-config] 解密字段 {} 失败（将原样传递，插件侧会表现为调用失败）：{}",
                        key, ex.getMessage());
                out.put(key, text);
            }
        }
        return out;
    }

    /**
     * 回显前的处理：敏感字段换成掩码（并保留明文是否存在的信号）。
     *
     * @return 可安全返回给前端的配置；空配置返回空 Map（不是 null）
     */
    public Map<String, Object> mask(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : stored.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!isSecretKey(key)) {
                out.put(key, value);
                continue;
            }
            String plain = revealOne(value);
            // 掩码而不是空串：前端据此显示"已配置"（空串会被读成"没配"）
            out.put(key, plain == null || plain.isBlank() ? "" : crypto.mask(plain));
        }
        return out;
    }

    private String revealOne(Object value) {
        if (!(value instanceof CharSequence cs)) {
            return value == null ? null : String.valueOf(value);
        }
        String text = cs.toString();
        if (!isWrapped(text)) {
            return text;
        }
        try {
            return crypto.decrypt(unwrap(text));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 判断某个配置键是否为"密钥类"字段。
     *
     * <p>用去掉分隔符后的精确匹配 + 后缀匹配，避免误伤 {@code maxTokens} 这类普通数值项
     * （它虽然含 "token"，但明显不是密钥；误加密会让插件拿到一串密文而无法工作）。</p>
     */
    static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase().replace("_", "").replace("-", "");
        return switch (k) {
            case "apikey", "secret", "token", "password", "accesstoken", "appsecret",
                 "apisecret", "clientsecret", "secretkey", "accesskey" -> true;
            default -> k.endsWith("apikey") || k.endsWith("secretkey") || k.endsWith("password");
        };
    }

    /** 是否是本类写出的密文。 */
    static boolean isWrapped(String text) {
        return text != null && text.startsWith(ENC_PREFIX);
    }

    /** 是否是掩码形态（例如 {@code sk-***abcd}）。 */
    static boolean looksMasked(String text) {
        return text != null && text.contains(MASK_TOKEN);
    }

    private static String wrap(String cipher) {
        return cipher == null ? null : ENC_PREFIX + cipher;
    }

    private static String unwrap(String text) {
        return text.substring(ENC_PREFIX.length());
    }
}
