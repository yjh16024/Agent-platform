package com.agentplatform.core.tool.builtin;

import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置 RAG 搜索工具。
 * <p>LLM 可经 function calling 触发，对知识库做混合检索并返回带引用的结果。</p>
 */
@Component("search")
public class SearchTool implements Tool {

    private final HybridRetriever retriever;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

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