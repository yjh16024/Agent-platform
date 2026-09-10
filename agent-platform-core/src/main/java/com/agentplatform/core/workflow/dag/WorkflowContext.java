package com.agentplatform.core.workflow.dag;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流上下文（变量作用域）。
 * <p>
 * 节点输入输出通过 {@code output_var} 写入上下文，下游用 {@code ${var.path}} 引用。
 * 支持全局作用域（工作流级变量）与节点写入。
 * </p>
 */
public class WorkflowContext {

    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    public WorkflowContext() {
    }

    public WorkflowContext(Map<String, Object> initial) {
        if (initial != null) {
            variables.putAll(initial);
        }
    }

    /**
     * 写入变量（节点 output_var）。
     */
    public void set(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * 读取变量。
     */
    public Object get(String key) {
        return variables.get(key);
    }

    /**
     * 解析 {@code ${var.path}} 占位符。
     * <p>支持 {@code ${var.path}}（点号路径访问嵌套 Map）与纯字面量。</p>
     */
    public Object resolve(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.matches("\\$\\{[^}]+}")) {
            String path = trimmed.substring(2, trimmed.length() - 1).trim();
            return resolvePath(path);
        }
        return expression;
    }

    /**
     * 递归解析表达式中的多个占位符（对整段文本）。
     */
    public String resolveString(String template) {
        if (template == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(template);
        while (m.find()) {
            Object value = resolvePath(m.group(1).trim());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 按点号路径访问变量（支持嵌套 Map/JsonNode）。
     */
    public Object resolvePath(String path) {
        String[] parts = path.split("\\.");
        Object current = variables.get(parts[0]);
        if (current == null) {
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            current = access(current, parts[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object access(Object obj, String key) {
        if (obj instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (obj instanceof JsonNode node) {
            JsonNode child = node.get(key);
            return child == null ? null : nodeValue(child);
        }
        return null;
    }

    private Object nodeValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node;
    }

    /**
     * 全量变量快照。
     */
    public Map<String, Object> all() {
        return new LinkedHashMap<>(variables);
    }
}