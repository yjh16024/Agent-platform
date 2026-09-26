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
 *
 * <h3>子作用域（2026-09-26 加，为 Parallel 节点服务）</h3>
 * <p>原实现只有**一个** Map，于是 {@code DagEngine} 并行跑多个下游时，各分支往同一张表里写 ——
 * 两个分支写同名 {@code output_var} 就成了竞态（谁后写谁赢，且不可复现）。</p>
 *
 * <p>现在支持挂一个 {@code parent}：<b>读会向上查找、写只落在本层</b>。
 * 并行分支各自持有一个子上下文（能看到父层已有变量，写入互不可见），
 * 由 {@code DagEngine} 按分支声明顺序把各分支的本层变量合并回父层 —— 结果与分支完成先后无关。</p>
 *
 * <p><b>无 parent 时行为与历史完全一致</b>（读不到就返回 null，不向上找）。</p>
 */
public class WorkflowContext {

    /** 本层变量。 */
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    /** 父作用域（null 表示根作用域）。 */
    private final WorkflowContext parent;

    public WorkflowContext() {
        this.parent = null;
    }

    public WorkflowContext(Map<String, Object> initial) {
        this.parent = null;
        if (initial != null) {
            variables.putAll(initial);
        }
    }

    /**
     * 创建一个子作用域：读可见父层，写只落本层。
     *
     * @param parent 父作用域（可为 null，等价于根作用域）
     */
    public WorkflowContext(WorkflowContext parent) {
        this.parent = parent;
    }

    /**
     * 写入变量（节点 output_var）。<b>只写本层</b>，不会污染父作用域。
     */
    public void set(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * 读取变量：本层没有则向上查父作用域。
     * <p>{@code key} 为 null 时返回 null —— 底层是 {@code ConcurrentHashMap}，
     * 它不接受 null 键（会抛 NPE），而调用方常有"节点没有 output_var"这种合法情形。</p>
     */
    public Object get(String key) {
        if (key == null) {
            return null;
        }
        Object value = variables.get(key);
        if (value != null) {
            return value;
        }
        return parent == null ? null : parent.get(key);
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
            // 本层没有根变量：整条路径交给父作用域解析（父也没有则继续向上，最终返回 null）
            return parent == null ? null : parent.resolvePath(path);
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
     * 全量变量快照（含父作用域；本层同名的覆盖父层）。
     * <p>{@code End} 节点返回的就是它，所以必须包含父层 —— 否则子作用域里跑的工作流
     * 会"看不见"上游变量。</p>
     */
    public Map<String, Object> all() {
        Map<String, Object> merged = parent == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(parent.all());
        merged.putAll(variables);
        return merged;
    }

    /**
     * <b>仅本层</b>变量快照（不含父作用域）。
     * <p>供 {@code DagEngine} 合并并行分支结果用 —— 只把它们自己写的东西带回去，
     * 不重复搬运父层已有变量。</p>
     */
    public Map<String, Object> localAll() {
        return new LinkedHashMap<>(variables);
    }

    /** 本层是否已有该变量（用于并行合并时的同名冲突告警）。 */
    public boolean localContains(String key) {
        return variables.containsKey(key);
    }

    /** 父作用域（根作用域返回 null）。 */
    public WorkflowContext parent() {
        return parent;
    }
}