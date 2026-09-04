package com.agentplatform.model.repository;

import com.agentplatform.model.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储。
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Optional<DocumentEntity> findByDocId(String docId);

    List<DocumentEntity> findByKbIdOrderByCreatedAtDesc(String kbId);

    /**
     * 删除知识库下的全部文档（知识库被删除时级联清理）。
     */
    void deleteByKbId(String kbId);

    /**
     * 删除单个文档。
     */
    void deleteByDocId(String docId);

    /**
     * 租户隔离校验：按租户 + 文档 ID 查询。
     */
    java.util.Optional<DocumentEntity> findByTenantIdAndDocId(String tenantId, String docId);
}