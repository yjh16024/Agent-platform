package com.agentplatform.core.tool;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.springai.SpringAiToolBridge;
import com.agentplatform.core.tool.builtin.SearchTool;
import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工具入参 schema 规范化（防「Invalid schema ... got 'type: null'」400 回归）。
 */
class ToolSchemasTest {

    @Test
    void nullSchemaBecomesValidObjectSchema() {
        JsonNode schema = ToolSchemas.orEmpty(null);
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.has("properties"), "必须有 properties 字段");
    }

    @Test
    void emptyObjectGetsTypeAndProperties() {
        JsonNode schema = ToolSchemas.orEmpty(JsonUtils.mapper().createObjectNode().put("x", 1));
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.has("properties"));
    }

    @Test
    void existingPropertiesArePreserved() {
        com.fasterxml.jackson.databind.node.ObjectNode raw = JsonUtils.mapper().createObjectNode();
        raw.putObject("properties").putObject("city").put("type", "string");
        JsonNode schema = ToolSchemas.orEmpty(raw);
        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.path("properties").path("city").path("type").asText());
    }

    @Test
    void searchToolDeclaresQuerySchema() {
        SearchTool tool = new SearchTool(mock(HybridRetriever.class));
        JsonNode schema = tool.inputSchema();
        assertNotNull(schema, "search 必须声明 schema");
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").has("query"));
        assertTrue(schema.path("required").toString().contains("query"));
    }

    /** Spring AI 通道：schema 为 null 的工具也必须声明成 type:object。 */
    @Test
    void bridgeExposesObjectSchemaForSchemaLessTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "no_schema_tool";
            }

            @Override
            public String description() {
                return "无入参工具";
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                return ToolResult.ok("ok");
            }
        });

        SpringAiToolBridge bridge = new SpringAiToolBridge(registry, null);
        List<org.springframework.ai.tool.ToolCallback> callbacks =
                bridge.callbacks(List.of(new ModelAdapter.ToolSpec("no_schema_tool", "无入参工具", null)));

        assertEquals(1, callbacks.size());
        String schema = callbacks.get(0).getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"type\":\"object\""), "下发给模型的 schema 必须是 object，实际=" + schema);
    }
}
