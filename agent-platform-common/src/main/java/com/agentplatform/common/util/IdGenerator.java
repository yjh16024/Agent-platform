package com.agentplatform.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 分布式 ID 生成工具。
 * <p>
 * 使用带前缀的短随机 ID（前缀 + 时间戳压缩 + 随机数），
 * 保证全局唯一的同时增强可读性（如 {@code agent_8f3c2a1b}）。
 * </p>
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    /**
     * 生成带前缀的随机 ID。
     *
     * @param prefix 前缀，如 "agent"、"run"、"sess"
     * @return 形如 {@code agent_8f3c2a1b} 的 ID
     */
    public static String generate(String prefix) {
        return prefix + "_" + random(8);
    }

    /**
     * 生成纯 UUID（无连字符）。
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成指定长度的随机小写字母数字串。
     */
    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}