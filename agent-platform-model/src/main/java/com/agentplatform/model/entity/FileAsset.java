package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GenerationType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件资产实体（多模态资源元数据）。
 */
@Entity
@Table(name = "file_asset")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "file_id", nullable = false, length = 64)
    private String fileId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "bucket", length = 128)
    private String bucket;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "file_type", length = 32)
    private String fileType;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}