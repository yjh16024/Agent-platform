package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * 运行日志索引实体（对应 {@code log_index} 表，V6 建表）。
 * <p>
 * 结构化日志的持久化载体：原本 {@code LogService} 仅用内存 {@code CopyOnWriteArrayList}
 * 存储，导致「运行日志页面为空 / 重启即丢」。改为落 MySQL 后，Agent 运行、LLM 调用、
 * 工具执行、插件 Hook 等埋点均可被查询、导出与瀑布图消费。
 * </p>
 */
@Entity
@Table(name = "log_index")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_id", nullable = false, length = 128)
    private String logId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "level", nullable = false, length = 16)
    private String level;

    @Column(name = "fingerprint", length = 256)
    private String fingerprint;

    @Column(name = "message", columnDefinition = "mediumtext")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "json")
    private Map<String, Object> context;

    @Column(name = "stack_trace", columnDefinition = "mediumtext")
    private String stackTrace;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "es_doc_id", length = 256)
    private String esDocId;
}
