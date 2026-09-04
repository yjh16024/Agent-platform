package com.agentplatform.core.log;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误指纹生成器（错误聚类 + 指纹）。
 * <p>基于「异常类型 + 关键 message + 上下文标签」生成稳定指纹，用于诊断规则匹配
 * 与错误聚类。同类型不同实例的错误生成相同指纹。</p>
 */
@Component
public class FingerprintGenerator {

    private static final Pattern STATUS_CODE = Pattern.compile("HTTP (\\d{3})|status[=:]\\s*(\\d{3})");
    private static final Pattern EXCEPTION = Pattern.compile("([A-Za-z]+(?:Exception|Error))");

    /**
     * 生成错误指纹。
     *
     * <p>规则（对齐 §3.5 诊断接口的表）：</p>
     * <ul>
     *   <li>插件类加载错误 → plugin#classloader_error</li>
     *   <li>插件超时 → plugin#timeout</li>
     *   <li>依赖冲突 → plugin#dependency_conflict</li>
     *   <li>模型连接错误 → model#connection_refused</li>
     *   <li>Skill 导入失败 → skill_import#manifest_invalid</li>
     *   <li>API 错误 → api#{status}#{path}</li>
     * </ul>
     */
    public String generate(LogCategory category, String message, String stackTrace) {
        String text = (message == null ? "" : message) + " " + (stackTrace == null ? "" : stackTrace);

        // 插件
        if (category == LogCategory.plugin) {
            if (contains(text, "ClassNotFoundException", "NoClassDefFoundError")) {
                return "plugin#classloader_error";
            }
            if (contains(text, "timeout", "TimeoutException", "timed out")) {
                return "plugin#timeout";
            }
            if (contains(text, "dependency", "conflict", "version range")) {
                return "plugin#dependency_conflict";
            }
            return "plugin#unknown";
        }

        // 模型
        if (category == LogCategory.llm) {
            if (contains(text, "ConnectException", "Connection refused", "SocketTimeout", "401")) {
                return "model#connection_refused";
            }
            return "model#unknown";
        }

        // Skill
        if (category == LogCategory.skill) {
            if (contains(text, "manifest", "yml", "yaml", "parse", "invalid")) {
                return "skill_import#manifest_invalid";
            }
            return "skill#unknown";
        }

        // API
        if (category == LogCategory.api) {
            Matcher status = STATUS_CODE.matcher(text);
            if (status.find()) {
                String code = status.group(1) != null ? status.group(1) : status.group(2);
                return "api#" + code;
            }
            return "api#unknown";
        }

        // 兜底：按异常类型
        Matcher exc = EXCEPTION.matcher(text);
        if (exc.find()) {
            return "exception#" + exc.group(1).toLowerCase();
        }
        return "generic#" + Integer.toHexString((category + message).hashCode());
    }

    private boolean contains(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}