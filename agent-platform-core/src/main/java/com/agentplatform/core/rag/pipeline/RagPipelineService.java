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

            // ④ 切块入库 + 向量化（解耦：chunk 内容始终入库；向量化失败仅告警，
            //    不丢弃内容，保证 UI 可浏览且关键词检索始终可用）
            int count = 0;
            int embedded = 0;
            for (ChunkSegment seg : segments) {
                Chunk chunk = buildChunk(kb, docId, fileType, seg);
                chunkRepository.save(chunk);
                count++;
                if (embedChunk(kb, chunk)) {
                    embedded++;
                }
            }

            doc.setStatus("indexed");
            doc.setChunkCount(count);
            documentRepository.save(doc);
            if (embedded == 0 && count > 0) {
                log.warn("Ingested document {} ({}) → {} chunks, but 0 embedded (embedding unavailable); "
                        + "keyword search only", fileName, kb.getKbId(), count);
            } else {
                log.info("Ingested document {} ({}) → {} chunks, {} embedded", fileName, kb.getKbId(), count, embedded);
            }
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
     * 构建 Chunk（仅元数据 + 内容，不依赖向量化，保证内容始终入库）。
     */
    private Chunk buildChunk(KnowledgeBase kb, String docId, String fileType, ChunkSegment seg) {
        String chunkId = IdGenerator.generate("chunk");
        Map<String, Object> meta = new LinkedHashMap<>(seg.metadata());
        meta.put("doc_id", docId);
        meta.put("kb_id", kb.getKbId());
        meta.put("file_type", fileType);

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

    /**
     * 向量化并写入向量库（尽力而为）。
     *
     * @return 是否嵌入成功
     */
    private boolean embedChunk(KnowledgeBase kb, Chunk chunk) {
        try {
            float[] vec = embeddingService.embed(chunk.getContent());
            vectorStore.upsert(chunk.getChunkId(), vec, chunk.getMeta());
            return true;
        } catch (Exception e) {
            log.warn("Embedding failed for chunk {} (content kept, vector skipped): {}",
                    chunk.getChunkId(), e.getMessage());
            return false;
        }
    }
}