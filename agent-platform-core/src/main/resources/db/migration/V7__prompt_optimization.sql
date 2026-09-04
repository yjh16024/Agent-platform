-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 7：提示词智能优化
-- ============================================================

-- ① 提示词优化历史记录
CREATE TABLE prompt_optimization (
    optimization_id  VARCHAR(128) NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    agent_id         VARCHAR(64),
    user_id          VARCHAR(64),
    raw_prompt       MEDIUMTEXT   NOT NULL,
    optimized_prompt MEDIUMTEXT   NOT NULL,
    diff             JSON,                           -- [{type, section, before, after}]
    score_before     DECIMAL(5,2),
    score_after      DECIMAL(5,2),
    dimensions       JSON,                           -- 6 维度评分
    source           VARCHAR(16),                    -- RULE / LLM / HYBRID
    target_model     VARCHAR(64),
    use_case         VARCHAR(64),
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (optimization_id),
    KEY idx_agent (tenant_id, agent_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 提示词优化规则表（可配置）
CREATE TABLE prompt_rule (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id   VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64)  DEFAULT '__platform__',
    name      VARCHAR(200),
    category  VARCHAR(32),                           -- role/structure/constraint/format/example/cot
    pattern   MEDIUMTEXT,                            -- 检测正则/条件
    template  MEDIUMTEXT,                            -- 增强模板
    priority  INT DEFAULT 100,
    enabled   TINYINT DEFAULT 1,
    created_at DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;