package com.agentplatform.core.workflow.node.executor;

import tools.jackson.databind.JsonNode;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.workflow.node.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点配置读取工具。
 * <p>
 * 节点 {@code config} 是自由 Map，按平台契约使用 snake_case（{@code kb_ids}、{@code tool_name}…）；
 * 为兼容画布早期字段与人工书写的定义，这里每个 getter 都接受多个候选 key
 * （例如 {@code kb_ids} 与 {@code kbIds}），按顺序取第一个非空值。
 * </p>
 */
final class NodeConfigs {

    private NodeConfigs() {
    }

    /** 按候选 key 取原始值。 */
    static Object raw(WorkflowNode node, String... keys) {
        Map<String, Object> config = node == null ? null : node.config();
        if (config == null || config.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object v = config.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 取字符串（null / 空白返回 null）。 */
    static String str(WorkflowNode node, String... keys) {
        Object v = raw(node, keys);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 取字符串，带默认值。 */
    static String strOr(WorkflowNode node, String def, String... keys) {
        String s = str(node, keys);
        return s == null ? def : s;
    }

    /** 取 int，带默认值（支持字符串数字）。 */
    static int intVal(WorkflowNode node, int def, String... keys) {
        Object v = raw(node, keys);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 取 double，带默认值。 */
    static double doubleVal(WorkflowNode node, double def, String... keys) {
        Object v = raw(node, keys);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 取字符串列表：支持 List，或逗号 / 换行分隔的字符串。
     * <p>画布上知识库 ID 用文本域填写，因此必须容忍分隔字符串。</p>
     */
    static List<String> list(WorkflowNode node, String... keys) {
        Object v = raw(node, keys);
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        for (String part : String.valueOf(v).split("[,\\n，]")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 取 Map：支持 Map，或 JSON 字符串；解析失败视为空 Map。
     */
    static Map<String, Object> map(WorkflowNode node, String... keys) {
        Object v = raw(node, keys);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), val));
            return out;
        }
        String json = String.valueOf(v).trim();
        if (json.isEmpty() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            JsonNode jsonNode = JsonUtils.toJsonNode(json);
            return JsonUtils.mapper().convertValue(jsonNode, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
