package com.agentplatform.model.entity;

import com.agentplatform.model.record.ModelBinding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 平台级模型配置（单行实体，id 固定为 1）。
 * <p>
 * 承载两类平台级默认绑定：
 * <ul>
 *   <li>{@code embeddingBinding} —— 嵌入模型（RAG 向量化）；</li>
 *   <li>{@code chatBinding} —— 默认对话模型（智能体未单独配置时回退到此）。</li>
 * </ul>
 * 语义与智能体的 {@code agent_def.model_binding} 一致：apiKey 以 AES-GCM 密文落库，
 * 运行时由 {@code ModelConfigService} 解密后直连厂商。空值表示未配置：
 * 嵌入退回本地 Mock（8 维伪向量），对话退回全局 LiteLLM 地址。
 * </p>
 */
@Entity
@Table(name = "model_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig {

    @Id
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedding_binding", columnDefinition = "json")
    private ModelBinding embeddingBinding;

    /** 平台级默认对话模型绑定（V10 新增）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chat_binding", columnDefinition = "json")
    private ModelBinding chatBinding;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}