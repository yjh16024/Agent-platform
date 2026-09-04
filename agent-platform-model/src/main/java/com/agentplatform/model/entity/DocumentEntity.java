package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GenerationType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 上传文档实体（元数据 + 处理状态）。
 */
@Entity
@Table(name = "document")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "file_type", length = 16)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_uri", length = 500)
    private String storageUri;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Lob
    @Column(name = "error_msg")
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}