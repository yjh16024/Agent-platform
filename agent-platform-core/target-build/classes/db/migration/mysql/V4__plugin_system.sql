-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 4：插件系统
-- ============================================================

-- ① 插件元数据（每个插件一行，含最新版本与审核状态）
CREATE TABLE plugin_def (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    plugin_id      VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,             -- 官方市场插件 tenant_id='__platform__'
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    author         VARCHAR(128),
    latest_version VARCHAR(32),
    manifest       JSON         NOT NULL,             -- 完整 plugin.yaml（contributes/requires/permissions）
    artifact_uri   VARCHAR(500),                      -- 制品地址（.zip / OCI）
    artifact_hash  VARCHAR(128),                      -- SHA-256，完整性校验
    status         VARCHAR(16)  DEFAULT 'draft',      -- draft|auditing|published|deprecated|archived
    visibility     VARCHAR(16)  DEFAULT 'private',
    scan_result    JSON,                              -- 静态扫描/漏洞结果
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_plugin (tenant_id, plugin_id),
    KEY idx_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 插件版本表（SemVer + 依赖 + 审核）
CREATE TABLE plugin_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    plugin_id    VARCHAR(64)  NOT NULL,
    version      VARCHAR(32)  NOT NULL,
    manifest     JSON         NOT NULL,
    artifact_uri VARCHAR(500),
    dependencies JSON,                              -- [{plugin_id, range}]
    audit_status VARCHAR(16)  DEFAULT 'pending',
    released_by  VARCHAR(64),
    released_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plugin_version (plugin_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ③ Agent ↔ Plugin 绑定（即"插入插件"关系，实例级配置）
CREATE TABLE agent_plugin (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id    VARCHAR(64)  NOT NULL,
    plugin_id   VARCHAR(64)  NOT NULL,
    version     VARCHAR(32),                         -- 钉选版本，NULL=最新兼容
    enabled     TINYINT      DEFAULT 1,
    config      JSON,                                -- 实例级参数 {voice, speed, ...}
    attached_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attached_by VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_plugin (agent_id, plugin_id),
    KEY idx_plugin (plugin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;