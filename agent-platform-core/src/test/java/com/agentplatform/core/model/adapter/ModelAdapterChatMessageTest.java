package com.agentplatform.core.model.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生 function calling 的消息拼装测试（2026-09-22）。
 *
 * <p>覆盖两件事：</p>
 * <ol>
 *   <li>{@link ModelAdapter.ChatMessage} 新增字段与工厂方法的语义；</li>
 *   <li>两个真实适配器把「assistant(tool_calls) + tool 结果」翻成各自协议时，
 *       <b>字段名与形态是否正确</b> —— 这是最容易被上游以 400 拒绝的一环，
 *       而且错了之后报错信息通常只说「invalid request」，极难定位。</li>
 * </ol>
 *
 * <p>适配器实例只用于调用纯转换方法，因此 {@code baseUrl}/{@code apiKey}/{@code httpClient}
 * 可以传占位值甚至 null（转换过程不碰网络）。</p>
 */
class ModelAdapterChatMessageTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final OpenAiCompatibleAdapter openai =
            new OpenAiCompatibleAdapter("test-openai", "http://localhost", "k", null, null);
    private final AnthropicAdapter anthropic =
            new AnthropicAdapter("test-anthropic", "http://localhost", "k", null, null);

    // ---------------- ChatMessage 语义 ----------------

    @Test
    @DisplayName("普通消息：两参构造器兼容旧调用，工具字段全空")
    void plainMessageHasNoToolFields() {
        ModelAdapter.ChatMessage m = new ModelAdapter.ChatMessage("user", "你好");

        assertEquals("user", m.role());
        assertEquals("你好", m.content());
        assertNull(m.toolCallId());
        assertTrue(m.toolCalls().isEmpty());
        assertFalse(m.hasToolCalls());
        assertFalse(m.isToolResult());
    }

    @Test
    @DisplayName("tool 工厂：role=tool 且带 toolCallId")
    void toolFactoryCarriesCallId() {
        ModelAdapter.ChatMessage m = ModelAdapter.ChatMessage.tool("call_1", "结果");

        assertTrue(m.isToolResult());
        assertEquals("call_1", m.toolCallId());
        assertEquals("结果", m.content());
        assertFalse(m.hasToolCalls());
    }

    @Test
    @DisplayName("assistantToolCalls 工厂：content 为空、带 toolCalls")
    void assistantToolCallsFactory() {
        ModelAdapter.ToolCall call =
                new ModelAdapter.ToolCall("call_1", "calc", JSON.objectNode().put("expr", "1+1"));
        ModelAdapter.ChatMessage m = ModelAdapter.ChatMessage.assistantToolCalls(List.of(call));

        assertEquals("assistant", m.role());
        assertNull(m.content(), "只请求工具时 content 应为空（这是 OpenAI 的合法形态）");
        assertTrue(m.hasToolCalls());
        assertEquals(1, m.toolCalls().size());
        assertEquals("call_1", m.toolCalls().get(0).id());
        assertFalse(m.isToolResult());
    }

    @Test
    @DisplayName("assistantToolCalls 传 null → 退化为空列表，不抛异常")
    void assistantToolCallsToleratesNull() {
        ModelAdapter.ChatMessage m = ModelAdapter.ChatMessage.assistantToolCalls(null);

        assertFalse(m.hasToolCalls());
    }

    // ---------------- OpenAI 协议形态 ----------------

    @Test
    @DisplayName("OpenAI：tool 结果 → role=tool + tool_call_id（缺了会被上游拒绝）")
    void openAiToolResultMessage() {
        Map<String, Object> msg = openai.toOpenAiMessage(ModelAdapter.ChatMessage.tool("call_9", "42"));

        assertEquals("tool", msg.get("role"));
        assertEquals("call_9", msg.get("tool_call_id"));
        assertEquals("42", msg.get("content"));
    }

    @Test
    @DisplayName("OpenAI：tool 结果 content 为 null → 退化成空串（该字段不能是 null）")
    void openAiToolResultNullContentBecomesEmpty() {
        Map<String, Object> msg = openai.toOpenAiMessage(ModelAdapter.ChatMessage.tool("call_9", null));

        assertEquals("", msg.get("content"));
    }

    @Test
    @DisplayName("OpenAI：assistant 请求工具 → tool_calls[] 且 arguments 是 JSON 字符串")
    void openAiAssistantToolCallsMessage() {
        JsonNode args = JSON.objectNode().put("expr", "1+1");
        ModelAdapter.ChatMessage m = ModelAdapter.ChatMessage.assistantToolCalls(
                List.of(new ModelAdapter.ToolCall("call_1", "calc", args)));

        Map<String, Object> msg = openai.toOpenAiMessage(m);

        assertEquals("assistant", msg.get("role"));
        assertNull(msg.get("content"), "带 tool_calls 时 content 允许为 null");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) msg.get("tool_calls");
        assertNotNull(calls);
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).get("id"));
        assertEquals("function", calls.get(0).get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) calls.get(0).get("function");
        assertEquals("calc", fn.get("name"));
        // OpenAI 要求 arguments 是**字符串**（JSON 文本），传对象会被 400
        assertTrue(fn.get("arguments") instanceof String, "arguments 必须是 JSON 字符串");
        assertTrue(((String) fn.get("arguments")).contains("1+1"));
    }

    @Test
    @DisplayName("OpenAI：toolCall 的 arguments 为 null → 给 {} 兜底")
    void openAiNullArgumentsFallback() {
        ModelAdapter.ChatMessage m = ModelAdapter.ChatMessage.assistantToolCalls(
                List.of(new ModelAdapter.ToolCall("call_1", "noop", null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls =
                (List<Map<String, Object>>) openai.toOpenAiMessage(m).get("tool_calls");
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) calls.get(0).get("function");

        assertEquals("{}", fn.get("arguments"));
    }

    // ---------------- Anthropic 协议形态 ----------------

    @Test
    @DisplayName("Anthropic：没有 role=tool，工具结果合并进一条 user 消息的 tool_result block")
    void anthropicMergesToolResultsIntoOneUserMessage() {
        List<ModelAdapter.ChatMessage> history = List.of(
                ModelAdapter.ChatMessage.assistantToolCalls(List.of(
                        new ModelAdapter.ToolCall("tu_1", "a", JSON.objectNode()),
                        new ModelAdapter.ToolCall("tu_2", "b", JSON.objectNode()))),
                ModelAdapter.ChatMessage.tool("tu_1", "结果A"),
                ModelAdapter.ChatMessage.tool("tu_2", "结果B"));

        List<Map<String, Object>> messages = anthropic.toAnthropicMessages(history);

        // assistant + 1 条合并后的 user = 2 条（两个 tool_result 必须成组）
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(1).get("content");
        assertEquals(2, blocks.size(), "两个工具结果应合并进同一条 user 消息");
        assertEquals("tool_result", blocks.get(0).get("type"));
        assertEquals("tu_1", blocks.get(0).get("tool_use_id"));
        assertEquals("结果A", blocks.get(0).get("content"));
        assertEquals("tu_2", blocks.get(1).get("tool_use_id"));
    }

    @Test
    @DisplayName("Anthropic：assistant 的 tool_use 是 content block 且 input 是对象")
    void anthropicAssistantToolUseIsContentBlock() {
        JsonNode input = JSON.objectNode().put("expr", "1+1");
        List<Map<String, Object>> messages = anthropic.toAnthropicMessages(List.of(
                ModelAdapter.ChatMessage.assistantToolCalls(
                        List.of(new ModelAdapter.ToolCall("tu_1", "calc", input)))));

        assertEquals(1, messages.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(1, blocks.size());
        assertEquals("tool_use", blocks.get(0).get("type"));
        assertEquals("tu_1", blocks.get(0).get("id"));
        assertEquals("calc", blocks.get(0).get("name"));
        // 与 OpenAI 相反：input 必须是**对象**，传字符串会被 400
        assertTrue(blocks.get(0).get("input") instanceof JsonNode, "input 应是 JSON 对象");
    }

    @Test
    @DisplayName("Anthropic：跳过 system 消息（它在顶层字段），普通消息原样保留")
    void anthropicSkipsSystemAndKeepsPlain() {
        List<Map<String, Object>> messages = anthropic.toAnthropicMessages(List.of(
                new ModelAdapter.ChatMessage("system", "系统提示"),
                new ModelAdapter.ChatMessage("user", "你好"),
                new ModelAdapter.ChatMessage("assistant", "在的")));

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("你好", messages.get(0).get("content"));
        assertEquals("assistant", messages.get(1).get("role"));
    }

    @Test
    @DisplayName("Anthropic：history 为 null → 返回空列表，不抛异常")
    void anthropicToleratesNullHistory() {
        assertTrue(anthropic.toAnthropicMessages(null).isEmpty());
    }
}
