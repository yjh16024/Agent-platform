package com.agentplatform.core.tool.builtin;

import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置 RAG 搜索工具。
 * <p>LLM 可经 function calling 触发，对知识库做混合检索并返回带引用的结果。</p>
 */
@Component("search")
public class SearchTool implements Tool {

    private final HybridRetriever retriever;
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    public SearchTool(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search the knowledge base for relevant documents given a query. Returns top chunks with citations.";
    }

    /**
     * 入参 schema：明确告知模型需要 {@code query}（可选 {@code knowledge_base_ids}）。
     * <p>不声明时厂商会报 {@code schema must be a JSON Schema of 'type: "object"'}。</p>
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "检索关键词或问题");
        ObjectNode kbIds = properties.putObject("knowledge_base_ids");
        kbIds.put("type", "array");
        kbIds.put("description", "可选：限定检索的知识库 ID 列表，留空表示全部");
        kbIds.putObject("items").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("query");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String query = args.path("query").asText();
        if (query == null || query.isBlank()) {
            return ToolResult.fail("Missing required argument 'query'");
        }
        List<String> kbIds = new java.util.ArrayList<>();
        JsonNode kbNode = args.path("knowledge_base_ids");
        if (kbNode.isArray()) {
            kbNode.forEach(n -> kbIds.add(n.asText()));
        }
        List<RetrievalResult> results = retriever.search(kbIds, query, 5, 0.0, null, true);

        ArrayNode arr = mapper.createArrayNode();
        for (RetrievalResult r : results) {
            ObjectNode item = mapper.createObjectNode();
            item.put("content", r.content());
            item.put("source", r.source());
            item.put("score", r.score());
            arr.add(item);
        }
        return ToolResult.ok(arr);
    }
}