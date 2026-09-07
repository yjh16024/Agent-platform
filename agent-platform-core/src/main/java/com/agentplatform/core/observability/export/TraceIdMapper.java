package com.agentplatform.core.observability.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Trace ID 归一化工具（应用内部 trace 字符串 ↔ W3C/OTel 16/32 位 hex）。
 * <p>
 * 应用内部使用 {@code trace_xxx} 形态的 traceId（人读友好、可入 MySQL），而
 * Tempo/Jaeger 与 Grafana 数据源联动要求 hex Trace ID。这里做确定性映射：
 * 32 位 hex 原样用；16 位 hex 补零到 32 位；其余任意串取 SHA-256 前 32 位，
 * 保证「同一应用 traceId → 同一 Tempo traceId」，日志与 Trace 可跨系统关联。
 * </p>
 */
public final class TraceIdMapper {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private TraceIdMapper() {
    }

    /** 把应用内部 traceId 映射为 32 位 hex（供 Tempo/Loki 关联使用）。 */
    public static String toHexTraceId(String appTraceId) {
        if (appTraceId == null || appTraceId.isBlank()) {
            return randomHex(16);
        }
        String v = appTraceId.trim();
        if (v.length() == 32 && isHex(v)) {
            return v;
        }
        if (v.length() == 16 && isHex(v)) {
            return "0000000000000000" + v;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(v.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            return randomHex(16);
        }
    }

    /** 生成随机 16 位 hex Span ID。 */
    public static String randomSpanId() {
        return randomHex(8);
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HEX.formatHex(b);
    }

    private static boolean isHex(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
