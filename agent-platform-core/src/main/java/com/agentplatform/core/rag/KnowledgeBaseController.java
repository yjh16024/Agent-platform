package com.agentplatform.core.rag;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（RESTful）。
 * <p>除增删改查与检索外，提供文档列表与 chunk 列表，支撑「浏览知识库具体内容」。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    /** 创建知识库。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer chunkOverlap,
            @RequestParam(required = false) String chunkStrategy) {
        com.agentplatform.model.entity.KnowledgeBase kb =
                kbService.create(tenantId, name, description, chunkSize, chunkOverlap, chunkStrategy);
        return ApiResponse.ok(Map.of("kbId", kb.getKbId(), "name", kb.getName()), "created");
    }

    /** 上传文档。 */
    @PostMapping("/{kbId}/documents")
    public ApiResponse<Integer> upload(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(kbService.uploadDocument(tenantId, kbId, file), "uploaded");
    }

    /** 检索。 */
    @PostMapping("/search")
    public ApiResponse<List<RetrievalResult>> search(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam List<String> kbIds,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "0.0") double scoreThreshold,
            @RequestParam(required = false) Map<String, Object> filter,
            @RequestParam(defaultValue = "true") boolean rerank) {
        return ApiResponse.ok(kbService.search(tenantId, kbIds, query, topK, scoreThreshold,
                sanitizeFilter(filter), rerank));
    }

    /**
     * 剔除检索参数本身，只保留真正的元数据过滤条件。
     * <p>
     * {@code @RequestParam Map} 会收集<b>全部</b>查询参数，若不剔除，{@code kbIds/query/topK} 等
     * 会被当成 chunk 元数据字段参与过滤（meta 中不存在这些键 → 全部 chunk 被过滤 → 检索恒为空）。
     * </p>
     */
    private Map<String, Object> sanitizeFilter(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        filter.remove("kbIds");
        filter.remove("query");
        filter.remove("topK");
        filter.remove("scoreThreshold");
        filter.remove("rerank");
        return filter.isEmpty() ? null : filter;
    }

    /** 列表（含文档数 / chunk 数）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(kbService.list(tenantId));
    }

    /** 详情（含文档数 / chunk 数）。 */
    @GetMapping("/{kbId}")
    public ApiResponse<Map<String, Object>> detail(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return ApiResponse.ok(kbService.detail(tenantId, kbId));
    }

    /** 知识库下的文档列表。 */
    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<Map<String, Object>>> documents(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return ApiResponse.ok(kbService.documents(tenantId, kbId));
    }

    /** 浏览切分块内容（可选按文档过滤）。 */
    @GetMapping("/{kbId}/chunks")
    public ApiResponse<List<Map<String, Object>>> chunks(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestParam(required = false) String docId,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(kbService.chunks(tenantId, kbId, docId, limit));
    }

    /** 删除单个文档（含其 chunk 与向量）。 */
    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> deleteDocument(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String docId) {
        kbService.deleteDocument(tenantId, docId);
        return ApiResponse.ok(null, "deleted");
    }

    /** 删除知识库（物理删除：文档 + chunk + 向量）。 */
    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        kbService.delete(tenantId, kbId);
        return ApiResponse.ok(null, "deleted");
    }
}
