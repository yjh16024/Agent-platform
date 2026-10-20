-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 3：工作流编排 + Skills
-- ============================================================

-- ① 工作流定义表
CREATE TABLE workflow_def (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    workflow_id  VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    definition   JSON         NOT NULL,              -- 完整 DAG 定义（nodes + edges）
    status       VARCHAR(16)  DEFAULT 'draft',      -- draft / published / archived
    version      VARCHAR(32),                       -- 语义化版本
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow (workflow_id),
    KEY idx_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② Skill 定义表（导入的 Skill 包元数据）
CREATE TABLE skill_def (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    skill_id     VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    version      VARCHAR(32),                       -- 语义化版本
    manifest     JSON         NOT NULL,             -- 完整 skill manifest（提示词+工具+工作流）
    source       VARCHAR(16),                       -- marketplace / git / local / url
    tools        JSON,                              -- 注册的工具名列表
    prompt_template MEDIUMTEXT,                     -- 提示词模板
    status       VARCHAR(16)  DEFAULT 'active',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill (skill_id),
    KEY idx_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;