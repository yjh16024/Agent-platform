package com.agentplatform.core.tool;

import com.agentplatform.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具入参 Schema 规范化。
 * <p>
 * 各厂商（OpenAI 兼容 / Anthropic）的 function calling 都要求 schema 为
 * {@code {"type":"object", ...}}；而平台允许工具不声明入参（{@link Tool#inputSchema()} 可空），
 * 直接把 {@code null} 或 {@code {}} 下发给模型会触发
 * {@code Invalid schema for function 'xxx': got 'type: null'} 400 错误。
 * </p>
 * <p>因此在「声明工具给模型」的最后一公里统一规范化：补 {@code type}、补 {@code properties}。</p>
 */
public final class ToolSchemas {

    /** 最宽松但合法的空 schema。 */
    public static final JsonNode EMPTY_OBJECT_SCHEMA =
            parse("{\"type\":\"object\",\"properties\":{},\"required\":[]}");

    private ToolSchemas() {
    }

    /**
     * 规范化：null / 非对象 → 空 object schema；对象但缺 {@code type} 或 {@code properties} → 补齐。
     * <p>注意返回的是<b>副本</b>，不会污染工具自身持有的 schema 节点。</p>
     */
    public static JsonNode orEmpty(JsonNode schema) {
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        ObjectNode node = (ObjectNode) schema.deepCopy();
        if (!node.hasNonNull("type")) {
            node.put("type", "object");
        }
        if (!node.has("properties")) {
            node.putObject("properties");
        }
        return node;
    }

    private static JsonNode parse(String json) {
        try {
            return JsonUtils.toJsonNode(json);
        } catch (Exception e) {
            // 极端兜底：手工构造，避免静态初始化失败导致类加载错误
            ObjectNode node = JsonUtils.mapper().createObjectNode();
            node.put("type", "object");
            node.putObject("properties");
            return node;
        }
    }
}
