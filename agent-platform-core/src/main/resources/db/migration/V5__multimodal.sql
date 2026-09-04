-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 4.5：多模态（文件资产 + 租户配额）
-- ============================================================

-- ① 文件资产表（multipart 上传的资源元数据）
CREATE TABLE file_asset (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    file_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    bucket      VARCHAR(128),                          -- OSS/MinIO bucket
    object_key  VARCHAR(500),                          -- 对象键
    file_name   VARCHAR(500),
    file_type   VARCHAR(32),                           -- image / audio / video / file
    mime_type   VARCHAR(128),
    file_size   BIGINT,
    status      VARCHAR(16)  DEFAULT 'uploaded',       -- uploaded / processed / failed
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file (file_id),
    KEY idx_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 租户配额表（模型调用 / 文件上传 / 插件 限额）
CREATE TABLE tenant_quota (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(64)  NOT NULL,
    quota_type    VARCHAR(32)  NOT NULL,               -- model_calls / tokens / files / plugins
    period        VARCHAR(16)  DEFAULT 'daily',        -- daily / monthly
    quota_limit   BIGINT       NOT NULL,
    used          BIGINT       DEFAULT 0,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota (tenant_id, quota_type, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;