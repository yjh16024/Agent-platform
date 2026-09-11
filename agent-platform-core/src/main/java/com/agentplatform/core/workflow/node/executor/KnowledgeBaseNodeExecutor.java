package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.rag.KnowledgeBaseService;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库节点执行器（画布「知识库」节点）。
 * <p>
 * 复用平台混合检索（{@link KnowledgeBaseService#search}）：向量 + FULLTEXT 融合 + 引用溯源。
 * 配置项（snake_case，兼容 camelCase）：
 * <ul>
 *   <li>{@code kb_ids} —— 知识库 ID，逗号或换行分隔（必填）</li>
 *   <li>{@code query} —— 检索语句，支持 {@code ${var}}（必填）</li>
 *   <li>{@code top_k} —— 返回条数，默认 5</li>
 *   <li>{@code score_threshold} —— 相似度下限，默认 0（不过滤）</li>
 * </ul>
 * 输出：{@code {count, chunks:[{chunk_id, content, score, source, page}]}}，供下游 {@code ${outputVar.chunks}} 引用。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseNodeExecutor implements NodeExecutor {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public NodeType type() {
        return NodeType.KnowledgeBase;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        List<String> kbIds = NodeConfigs.list(node, "kb_ids", "kbIds", "knowledge_base_ids");
        if (kbIds.isEmpty()) {
            throw new IllegalArgumentException("KnowledgeBase node " + node.id() + " requires config.kb_ids");
        }
        String query = NodeConfigs.str(node, "query", "q");
        if (NodeConfigs.blank(query)) {
            throw new IllegalArgumentException("KnowledgeBase node " + node.id() + " requires config.query");
        }
        String rendered = ctx.resolveString(query);
        int topK = NodeConfigs.intVal(node, 5, "top_k", "topK");
        double threshold = NodeConfigs.doubleVal(node, 0.0, "score_threshold", "scoreThreshold");

        List<RetrievalResult> results =
                knowledgeBaseService.search("default", kbIds, rendered, topK, threshold, null, true);

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (RetrievalResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", r.chunkId());
            item.put("content", r.content());
            item.put("score", r.score());
            item.put("source", r.source());
            item.put("page", r.page());
            chunks.add(item);
        }
        log.debug("KnowledgeBase node {} retrieved {} chunks (kbIds={})", node.id(), chunks.size(), kbIds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", chunks.size());
        out.put("chunks", chunks);
        return out;
    }
}
