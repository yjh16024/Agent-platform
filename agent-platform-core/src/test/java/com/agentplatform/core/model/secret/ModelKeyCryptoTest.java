package com.agentplatform.core.model.secret;

import com.agentplatform.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型 API Key 加解密与掩码测试。
 */
class ModelKeyCryptoTest {

    private ModelKeyCrypto crypto;

    @BeforeEach
    void setUp() {
        crypto = new ModelKeyCrypto("test-master-key");
    }

    @Test
    @DisplayName("加密解密回环一致")
    void encryptDecryptRoundtrip() {
        String plain = "sk-abc1234567890xyz";
        String enc = crypto.encrypt(plain);
        assertNotEquals(plain, enc, "密文不应等于明文");
        assertEquals(plain, crypto.decrypt(enc));
    }

    @Test
    @DisplayName("同明文每次加密得到不同密文（随机 IV）")
    void encryptIsRandomized() {
        String plain = "sk-same-plaintext";
        assertNotEquals(crypto.encrypt(plain), crypto.encrypt(plain));
    }

    @Test
    @DisplayName("空值透传不加密")
    void blankPassThrough() {
        assertNull(crypto.encrypt(null));
        assertEquals("", crypto.encrypt(""));
        assertNull(crypto.decrypt(null));
    }

    @Test
    @DisplayName("篡改密文解密抛异常")
    void tamperedCiphertextThrows() {
        String enc = crypto.encrypt("sk-secret");
        String tampered = enc.substring(0, enc.length() - 2) + "AB";
        assertThrows(BizException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    @DisplayName("主密钥不一致解密抛异常")
    void wrongKeyFailsToDecrypt() {
        String enc = crypto.encrypt("sk-secret");
        ModelKeyCrypto other = new ModelKeyCrypto("another-master-key");
        assertThrows(BizException.class, () -> other.decrypt(enc));
    }

    @Test
    @DisplayName("掩码只保留前缀与末 4 位")
    void maskKeepsTailOnly() {
        assertEquals("sk-***abcd", crypto.mask("sk-12345678abcd"));
        assertEquals("***", crypto.mask("short"));
        assertNull(crypto.mask(null));
    }
}