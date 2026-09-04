package com.agentplatform.core.model.secret;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型 API Key 的加解密与掩码（AES-256-GCM）。
 * <p>
 * API Key 在仪表盘填写后即落库，为避免拖库泄露，统一经本组件加密存密文、
 * 运行时解密发往模型厂商、对外只暴露掩码。主密钥来自
 * {@code agent-platform.secrets.model-key}（env {@code MODEL_KEY_ENC_KEY}）。
 * </p>
 * <p>
 * 密文格式：{@code base64( 12 字节随机 IV || GCM 密文含 128-bit 认证标签) }。
 * 篡改密文或密钥不一致时 GCM 认证失败，统一抛出 {@link BizException}，由模型
 * 路由的宕机降级（Mock 兜底）接住，不影响主流程。
 * </p>
 */
@Slf4j
@Component
public class ModelKeyCrypto {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public ModelKeyCrypto(@Value("${agent-platform.secrets.model-key}") String masterKey) {
        this.key = deriveKey(masterKey == null ? "" : masterKey);
    }

    /** 由任意长度口令派生 256-bit AES 密钥（SHA-256 定长化）。 */
    private static SecretKeySpec deriveKey(String masterKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw BizException.internal("无法初始化模型密钥加密组件", e);
        }
    }

    /** 加密：明文 → base64(iv+cipher)。空值原样返回。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(encrypted, 0, out, IV_LEN, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw BizException.internal("加密 API Key 失败", e);
        }
    }

    /** 解密：base64(iv+cipher) → 明文。空值原样返回。 */
    public String decrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return enc;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(enc);
            if (raw.length <= IV_LEN) {
                throw BizException.internal("API Key 密文格式非法");
            }
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LEN));
            byte[] plain = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // GCM 认证失败（密文被篡改 / 主密钥不一致）
            throw BizException.internal("解密 API Key 失败（密文或主密钥不匹配）", e);
        }
    }

    /** 掩码展示：保留前 3 位与末 4 位，如 {@code sk-***abcd}。 */
    public String mask(String plain) {
        if (plain == null) {
            return null;
        }
        if (plain.isBlank()) {
            return "";
        }
        if (plain.length() <= 8) {
            return "***";
        }
        return plain.substring(0, 3) + "***" + plain.substring(plain.length() - 4);
    }
}