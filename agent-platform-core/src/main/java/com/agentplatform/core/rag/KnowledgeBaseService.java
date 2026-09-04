package com.agentplatform.core.rag;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.rag.pipeline.RagPipelineService;
import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.core.rag.retriever.VectorStore;
import com.agentplatform.model.entity.Chunk;
import com.agentplatform.model.entity.DocumentEntity;
import com.agentplatform.model.entity.KnowledgeBase;
import com.agentplatform.model.repository.ChunkRepository;
import com.agentplatform.model.repository.DocumentRepository;
import com.agentplatform.model.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库服务（生命周期：创建 → 文档上传 → 解析 → 切分 → 向量化 → 索引 → 检索 → 删除）。
 * <p>
 * 删除为<b>物理删除</b>：级联清理文档、切分块与向量，避免「页面仍在但已归档」的
 * 悬空状态（原先只置 status=archived，而列表不过滤状态，导致删除后仍可见）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final RagPipelineService pipelineService;
    private final HybridRetriever hybridRetriever;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorStore vectorStore;

    /**
     * 创建知识库。
     */
    @Transactional
    public KnowledgeBase create(String tenantId, String name, String description,
                                Integer chunkSize, Integer chunkOverlap, String chunkStrategy) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .kbId(IdGenerator.generate("kb"))
                .tenantId(tenantId)
                .name(name)
                .description(description)
                .embeddingModel("text-embedding-ada-002")
                .chunkSize(chunkSize == null ? 512 : chunkSize)
                .chunkOverlap(chunkOverlap == null ? 50 : chunkOverlap)
                .chunkStrategy(chunkStrategy == null ? "recursive" : chunkStrategy)
                .status("active")
                .build();
        return kbRepository.save(kb);
    }

    /**
     * 上传文档并触发摄取管线。
     */
    @Transactional
    public int uploadDocument(String tenantId, String kbId, MultipartFile file) {
        KnowledgeBase kb = getOrThrow(tenantId, kbId);
        try {
            return pipelineService.ingest(kb, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw BizException.internal("Failed to read upload file", e);
        }
    }

    /**
     * RAG 检索。
     */
    @Transactional(readOnly = true)
    public List<RetrievalResult> search(String tenantId, List<String> kbIds, String query,
                                        int topK, double scoreThreshold, Map<String, Object> filter, boolean rerank) {
        return hybridRetriever.search(kbIds, query, topK, scoreThreshold, filter, rerank);
    }

    /**
     * 查询知识库列表（含文档数与 chunk 数统计）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String tenantId) {
        return kbRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 知识库详情（含文档数 / chunk 数）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String tenantId, String kbId) {
        return toSummary(getOrThrow(tenantId, kbId));
    }

    /**
     * 列出知识库下的文档（供「浏览知识库内容」）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> documents(String tenantId, String kbId) {
        getOrThrow(tenantId, kbId);
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::toDocumentSummary)
                .toList();
    }

    /**
     * 列出知识库/文档的切分块（供「浏览知识库具体内容」）。
     *
     * @param docId 为空时返回整个知识库的 chunk（按文档 + 序号排序）
     * @param limit 最大返回条数（默认 200，防止超大知识库撑爆响应）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> chunks(String tenantId, String kbId, String docId, int limit) {
        getOrThrow(tenantId, kbId);
        List<Chunk> rows = (docId == null || docId.isBlank())
                ? chunkRepository.findByKbIdOrderBySeqNo(kbId)
                : chunkRepository.findByDocIdOrderBySeqNo(docId);
        int max = limit <= 0 ? 200 : Math.min(limit, 2000);
        return rows.stream().limit(max).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.getChunkId());
            m.put("docId", c.getDocId());
            m.put("seqNo", c.getSeqNo());
            m.put("content", c.getContent());
            m.put("meta", c.getMeta());
            return m;
        }).toList();
    }

    /**
     * 删除单个文档（含其切分块与向量）。
     */
    @Transactional
    public void deleteDocument(String tenantId, String docId) {
        DocumentEntity doc = documentRepository.findByTenantIdAndDocId(tenantId, docId)
                .orElseThrow(() -> BizException.notFound("document", docId));
        deleteDocumentCascade(doc);
    }

    /**
     * 删除知识库（物理删除：文档 + 切分块 + 向量 + 知识库本身）。
     */
    @Transactional
    public void delete(String tenantId, String kbId) {
        KnowledgeBase kb = getOrThrow(tenantId, kbId);
        for (DocumentEntity doc : documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)) {
            deleteDocumentCascade(doc);
        }
        // 兜底：清掉可能残留的 chunk 与向量
        chunkRepository.deleteByKbId(kbId);
        vectorStore.deleteByFilter("kb_id", kbId);
        kbRepository.delete(kb);
        log.info("Deleted knowledge base {} [{}]", kb.getName(), kbId);
    }

    // ---- 内部 ----

    private void deleteDocumentCascade(DocumentEntity doc) {
        for (Chunk c : chunkRepository.findByDocIdOrderBySeqNo(doc.getDocId())) {
            try {
                vectorStore.delete(c.getChunkId());
            } catch (Exception e) {
                log.warn("Failed to delete vector {}: {}", c.getChunkId(), e.getMessage());
            }
        }
        chunkRepository.deleteByDocId(doc.getDocId());
        documentRepository.delete(doc);
        log.info("Deleted document {} [{}]", doc.getTitle(), doc.getDocId());
    }

    /**
     * 汇总视图（camelCase，与前端既有 KnowledgeBase 契约一致，另附统计字段）。
     */
    private Map<String, Object> toSummary(KnowledgeBase kb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbId", kb.getKbId());
        m.put("tenantId", kb.getTenantId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("embeddingModel", kb.getEmbeddingModel());
        m.put("chunkSize", kb.getChunkSize());
        m.put("chunkOverlap", kb.getChunkOverlap());
        m.put("chunkStrategy", kb.getChunkStrategy());
        m.put("status", kb.getStatus());
        m.put("createdAt", kb.getCreatedAt());
        m.put("updatedAt", kb.getUpdatedAt());
        m.put("documentCount", documentRepository.findByKbIdOrderByCreatedAtDesc(kb.getKbId()).size());
        m.put("chunkCount", chunkRepository.countByKbId(kb.getKbId()));
        return m;
    }

    private Map<String, Object> toDocumentSummary(DocumentEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", d.getDocId());
        m.put("kbId", d.getKbId());
        m.put("title", d.getTitle());
        m.put("fileName", d.getFileName());
        m.put("fileType", d.getFileType());
        m.put("fileSize", d.getFileSize());
        m.put("status", d.getStatus());
        m.put("chunkCount", d.getChunkCount());
        m.put("errorMsg", d.getErrorMsg());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private KnowledgeBase getOrThrow(String tenantId, String kbId) {
        return kbRepository.findByTenantIdAndKbId(tenantId, kbId)
                .orElseThrow(() -> BizException.notFound("knowledge base", kbId));
    }
}
