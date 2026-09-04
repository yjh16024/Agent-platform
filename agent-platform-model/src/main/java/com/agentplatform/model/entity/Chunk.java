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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档切分块（Chunk）。
 * <p>MySQL 存元数据 + 内容 + 引用溯源；向量存 Milvus（external_id 关联）。</p>
 */
@Entity
@Table(name = "chunk")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "mediumtext")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", columnDefinition = "json")
    private Map<String, Object> meta;

    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}