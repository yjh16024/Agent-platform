package com.agentplatform.core.rag;

import com.agentplatform.core.rag.pipeline.RagPipelineService;
import com.agentplatform.model.entity.KnowledgeBase;
import com.agentplatform.model.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话附件自动摄取（方案 C：大文件/文档归入知识库检索并带引用）。
 * <p>
 * 对话拖入文档类附件时，懒建「租户级」附件知识库（每个租户一个），按 fileId 幂等摄取一次；
 * 摄取后检索入口把该库并入范围，命中即带引用返回。任何失败降级：不影响文本对话。
 * </p>
 * <p><b>边界</b>：fileId 去重为进程内内存态（重启后同文件再次拖入会重新摄取，产生重复 chunk）；
 * 生产改进：以「fileName+size+sha」写入 DB 判重。</p>
 */
@Slf4j
@Service
public class ConversationAttachmentService {

    /** 附件库固定名（租户级一个）。 */
    public static final String KB_NAME = "__chat_attachments__";

    /** 视为可摄取文档的扩展名（与 RAG 解析管线保持一致子集）。 */
    private static final String[] DOC_EXTS = {
            "txt", "md", "markdown", "pdf", "doc", "docx", "ppt", "pptx",
            "xls", "xlsx", "csv", "html", "htm", "json", "xml"
    };

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseService kbService;
    private final RagPipelineService pipeline;

    private final ConcurrentHashMap<String, String> kbCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> ingested = new ConcurrentHashMap<>();

    public ConversationAttachmentService(KnowledgeBaseRepository kbRepository,
                                         KnowledgeBaseService kbService,
                                         RagPipelineService pipeline) {
        this.kbRepository = kbRepository;
        this.kbService = kbService;
        this.pipeline = pipeline;
    }

    /** 文件名是否文档类（可自动摄取）。 */
    public static boolean isDocumentFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : DOC_EXTS) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 取租户附件库 ID（懒建 + 内存缓存；失败返回 null）。 */
    public String attachmentKbId(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        return kbCache.computeIfAbsent(tenantId, this::locateOrCreate);
    }

    private String locateOrCreate(String tenantId) {
        try {
            for (KnowledgeBase kb : kbRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)) {
                if (KB_NAME.equals(kb.getName()) && "active".equals(kb.getStatus())) {
                    return kb.getKbId();
                }
            }
            KnowledgeBase created = kbService.create(tenantId, KB_NAME,
                    "对话拖入的文档自动摄取区（勿手动改名）", 512, 50, "recursive");
            log.info("Created chat-attachment KB {} for tenant {}", created.getKbId(), tenantId);
            return created.getKbId();
        } catch (Exception e) {
            log.warn("Locate/create attachment KB failed for {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    /** 摄取附件到租户附件库（按 fileId 幂等；失败降级不抛出）。 */
    public void ensureDocument(String tenantId, String fileId, String fileName, byte[] content) {
        if (tenantId == null || fileId == null || content == null || content.length == 0
                || kbService == null || pipeline == null) {
            return;
        }
        String kbId = attachmentKbId(tenantId);
        if (kbId == null) {
            return;
        }
        if (ingested.putIfAbsent(fileId, kbId) != null) {
            return;
        }
        try {
            KnowledgeBase kb = kbRepository.findByTenantIdAndKbId(tenantId, kbId).orElse(null);
            if (kb == null) {
                kbCache.remove(tenantId);
                ingested.remove(fileId);
                return;
            }
            int chunks = pipeline.ingest(kb, fileName, content);
            log.info("Ingested chat attachment {} -> {} chunks into KB {}", fileName, chunks, kbId);
        } catch (Exception e) {
            ingested.remove(fileId);
            log.warn("Ingest chat attachment {} failed: {}", fileName, e.getMessage());
        }
    }
}
