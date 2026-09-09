-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 6：运行日志与智能诊断
-- ============================================================

-- ① 日志索引表（热数据在 ES/ClickHouse，MySQL 存关键索引 + 审计）
CREATE TABLE log_index (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    log_id        VARCHAR(128) NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    trace_id      VARCHAR(128),
    run_id        VARCHAR(128),
    agent_id      VARCHAR(64),
    category      VARCHAR(32)  NOT NULL,             -- agent/llm/plugin/skill/api/workflow/system
    level         VARCHAR(16)  NOT NULL,             -- TRACE/DEBUG/INFO/WARN/ERROR
    fingerprint   VARCHAR(256),                      -- 错误指纹（用于诊断匹配）
    message       MEDIUMTEXT,
    context       JSON,
    timestamp     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    es_doc_id     VARCHAR(256),
    PRIMARY KEY (id),
    UNIQUE KEY uk_log (log_id),
    KEY idx_trace (tenant_id, trace_id, timestamp),
    KEY idx_run (tenant_id, run_id, timestamp),
    KEY idx_fp (tenant_id, fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 诊断报告表（每次诊断结果持久化）
CREATE TABLE diagnosis_report (
    report_id      VARCHAR(128) NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    trace_id       VARCHAR(128),
    log_id         VARCHAR(128),
    fingerprint    VARCHAR(256) NOT NULL,
    source         VARCHAR(16)  NOT NULL,            -- RULE / VECTOR / LLM
    severity       VARCHAR(16),
    category       VARCHAR(32),
    root_cause     JSON         NOT NULL,            -- {summary, detail, evidence}
    solutions      JSON         NOT NULL,            -- [{title, description, type, confidence}]
    confidence     DECIMAL(4,3),
    knowledge_ref  VARCHAR(500),
    is_helpful     TINYINT,                          -- 用户反馈（闭环沉淀依据）
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id),
    KEY idx_fingerprint (tenant_id, fingerprint),
    KEY idx_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ③ 诊断规则表（可配置的错误-根因-方案映射）
CREATE TABLE diagnosis_rule (
    rule_id     VARCHAR(128) NOT NULL,
    tenant_id   VARCHAR(64)  DEFAULT '__platform__',
    name        VARCHAR(200) NOT NULL,
    category    VARCHAR(32),
    fingerprint VARCHAR(256),
    conditions  JSON         NOT NULL,               -- 条件表达式
    root_cause  MEDIUMTEXT,
    solutions   JSON         NOT NULL,
    confidence  DECIMAL(4,3) DEFAULT 0.9,
    enabled     TINYINT      DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (rule_id),
    KEY idx_category (tenant_id, category, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;