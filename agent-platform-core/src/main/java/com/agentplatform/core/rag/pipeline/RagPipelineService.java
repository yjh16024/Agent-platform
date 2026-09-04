package com.agentplatform.core.rag.pipeline;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.rag.chunker.Chunker;
import com.agentplatform.core.rag.chunker.ChunkerFactory;
import com.agentplatform.core.rag.chunker.ChunkSegment;
import com.agentplatform.core.rag.retriever.EmbeddingService;
import com.agentplatform.core.rag.retriever.VectorStore;
import com.agentplatform.model.entity.Chunk;
import com.agentplatform.model.entity.DocumentEntity;
import com.agentplatform.model.entity.KnowledgeBase;
import com.agentplatform.model.repository.ChunkRepository;
import com.agentplatform.model.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 摄取管线（parse → chunk → embed → index）。
 * <p>
 * 全流程：文档解析（Tika）→ 切分（模板方法 + 策略）→ 向量化（EmbeddingService）
 * → 索引（MySQL 存元数据 + VectorStore 存向量）→ 引用溯源元数据（页码/标题）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPipelineService {

    private final DocumentParser documentParser;
    private final ChunkerFactory chunkerFactory;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    /**
     * 摄取文档（解析 + 切分 + 向量化 + 索引）。
     *
     * @param kb       知识库
     * @param fileName 文件名
     * @param content  原始字节
     * @return 产生的 chunk 数量
     */
    @Transactional
    public int ingest(KnowledgeBase kb, String fileName, byte[] content) {
        String fileType = documentParser.inferType(fileName);
        String docId = IdGenerator.generate("doc");

        // ① 记录文档（status=parsing）
        DocumentEntity doc = DocumentEntity.builder()
                .docId(docId)
                .kbId(kb.getKbId())
                .tenantId(kb.getTenantId())
                .title(fileName)
                .fileName(fileName)
                .fileType(fileType)
                .fileSize((long) content.length)
                .status("parsing")
                .build();
        documentRepository.save(doc);

        try {
            // ② 解析
            String text = documentParser.parse(fileName, content, fileType);
            // ③ 切分
            Chunker chunker = chunkerFactory.get(kb.getChunkStrategy());
            Chunker.ChunkConfig config = new Chunker.ChunkConfig(
                    kb.getChunkSize() == null ? 512 : kb.getChunkSize(),
                    kb.getChunkOverlap() == null ? 50 : kb.getChunkOverlap(),
                    Chunker.ChunkConfig.defaults().separators());
            List<ChunkSegment> segments = chunker.chunk(text, config);

            // ④ 向量化 + 索引
            int count = 0;
            for (ChunkSegment seg : segments) {
                Chunk chunk = indexSegment(kb, docId, fileType, content.length, seg);
                count++;
                if (chunk != null) {
                    chunkRepository.save(chunk);
                }
            }

            doc.setStatus("indexed");
            doc.setChunkCount(count);
            documentRepository.save(doc);
            log.info("Ingested document {} ({}) → {} chunks", fileName, kb.getKbId(), count);
            return count;
        } catch (Exception e) {
            doc.setStatus("failed");
            doc.setErrorMsg(e.getMessage());
            documentRepository.save(doc);
            log.error("Ingest failed for {}: {}", fileName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 单个段：构建 Chunk 元数据 + 向量入库。
     */
    private Chunk indexSegment(KnowledgeBase kb, String docId, String fileType,
                               long fileSize, ChunkSegment seg) {
        String chunkId = IdGenerator.generate("chunk");
        Map<String, Object> meta = new LinkedHashMap<>(seg.metadata());
        meta.put("doc_id", docId);
        meta.put("kb_id", kb.getKbId());
        meta.put("file_type", fileType);

        // 向量入库（external_id = chunkId）
        try {
            float[] vec = embeddingService.embed(seg.content());
            vectorStore.upsert(chunkId, vec, meta);
        } catch (Exception e) {
            log.warn("Embedding failed for chunk {} (skip): {}", chunkId, e.getMessage());
            return null;
        }

        return Chunk.builder()
                .chunkId(chunkId)
                .kbId(kb.getKbId())
                .docId(docId)
                .tenantId(kb.getTenantId())
                .seqNo(seg.seqNo())
                .content(seg.content())
                .meta(meta)
                .externalId(chunkId)
                .build();
    }
}