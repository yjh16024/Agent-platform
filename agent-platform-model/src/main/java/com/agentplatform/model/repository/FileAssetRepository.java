package com.agentplatform.model.repository;

import com.agentplatform.model.entity.FileAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文件资产仓储。
 */
@Repository
public interface FileAssetRepository extends JpaRepository<FileAsset, Long> {

    Optional<FileAsset> findByFileId(String fileId);

    List<FileAsset> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}