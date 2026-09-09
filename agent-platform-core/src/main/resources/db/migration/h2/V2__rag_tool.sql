-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 2：RAG 知识库 + 工具注册中心
-- ============================================================

-- ① 知识库表
CREATE TABLE knowledge_base (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id          VARCHAR(64)  NOT NULL,
    tenant_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    embedding_model VARCHAR(64) DEFAULT 'text-embedding-ada-002',
    chunk_size     INT          DEFAULT 512,
    chunk_overlap  INT          DEFAULT 50,
    chunk_strategy VARCHAR(32)  DEFAULT 'recursive',      -- recursive / semantic / structural
    status         VARCHAR(16)  DEFAULT 'active',         -- active / archived
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb (kb_id),
    KEY idx_tenant_1 (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 文档表（上传的源文档元数据）
CREATE TABLE document (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id       VARCHAR(64)  NOT NULL,
    kb_id        VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    title        VARCHAR(500),
    file_name    VARCHAR(500),
    file_type    VARCHAR(16),                               -- pdf / docx / pptx / xlsx / md / txt / html
    file_size    BIGINT,
    storage_uri  VARCHAR(500),                              -- OSS/MinIO 地址
    status       VARCHAR(16)  DEFAULT 'pending',            -- pending / parsing / indexed / failed
    chunk_count  INT          DEFAULT 0,
    error_msg    TEXT,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc (doc_id),
    KEY idx_kb_1 (kb_id, status),
    KEY idx_tenant_2 (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ③ Chunk 表（MySQL 存元数据 + 引用溯源；向量存 Milvus，通过 external_id 关联）
CREATE TABLE chunk (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    chunk_id      VARCHAR(64)  NOT NULL,
    kb_id         VARCHAR(64)  NOT NULL,
    doc_id        VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    seq_no        INT          NOT NULL,                    -- 切分序号（保持顺序）
    content       MEDIUMTEXT   NOT NULL,
    meta          JSON,                                     -- {page, heading, table, ...}
    external_id   VARCHAR(128),                             -- Milvus 向量 external_id
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk (chunk_id),
    KEY idx_doc (doc_id, seq_no),
    KEY idx_kb_2 (kb_id)
    -- H2 无 FULLTEXT：稀疏检索在 MySQL 走 MATCH..AGAINST；H2 由 HybridRetriever 顺序扫描兜底
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ④ 工具注册表
CREATE TABLE tool_def (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tool_id      VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    tool_type    VARCHAR(16)  NOT NULL,                     -- builtin / http / mcp / script
    input_schema JSON,                                      -- JSON Schema
    endpoint     VARCHAR(500),                              -- http 工具地址（tool_type=http）
    method       VARCHAR(16),                               -- GET/POST（http 工具）
    auth_config  JSON,                                      -- 鉴权（header/key）
    enabled      TINYINT      DEFAULT 1,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool (tool_id),
    KEY idx_tenant_3 (tenant_id, enabled),
    KEY idx_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;