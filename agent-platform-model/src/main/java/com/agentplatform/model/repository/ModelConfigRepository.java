package com.agentplatform.model.repository;

import com.agentplatform.model.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 平台级模型配置仓库（单行，id 固定为 1）。
 */
public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {
}