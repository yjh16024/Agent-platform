package com.agentplatform.core.security.rbac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希测试。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenMatchSucceeds() {
        String hash = hasher.hash("s3cret-password");
        assertTrue(hasher.matches("s3cret-password", hash));
    }

    /** BCrypt 自带随机盐：同一个密码两次哈希必须不同（否则等于没用盐）。 */
    @Test
    void sameInputProducesDifferentHashes() {
        String a = hasher.hash("same");
        String b = hasher.hash("same");
        assertNotEquals(a, b, "BCrypt 每次哈希都应使用新的随机盐");
        assertTrue(hasher.matches("same", a));
        assertTrue(hasher.matches("same", b));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        assertFalse(hasher.matches("wrong", hasher.hash("right")));
    }

    /**
     * 库里存了非 BCrypt 格式的值（例如手工插入的明文）时，
     * 必须返回 false 而不是抛异常 —— 这是"数据脏了"而不是"服务故障"，
     * 抛出去会让用户看到 500，把排查方向引偏。
     */
    @Test
    void malformedHashReturnsFalseInsteadOfThrowing() {
        assertFalse(hasher.matches("whatever", "plain-text-password"));
        assertFalse(hasher.matches("whatever", "$2a$not-a-valid-hash"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(hasher.matches(null, hasher.hash("x")));
        assertFalse(hasher.matches("x", null));
        assertFalse(hasher.matches("x", ""));
        assertFalse(hasher.matches("x", "   "));
    }
}
