-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- Phase 1：智能体管理基座表
-- 说明：MySQL 8.x 原生 JSON 类型承载半结构化字段；
--       向量数据独立存 Milvus，MySQL 侧仅保存 external_id 关联。
-- ============================================================

-- ① 智能体定义表（当前值）
CREATE TABLE agent_def (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id         VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    avatar           VARCHAR(500),
    description      TEXT,
    persona          JSON         NOT NULL,             -- {tone,style,role,warmth,expertise,forbidden,...}
    system_prompt    MEDIUMTEXT   NOT NULL,             -- 系统提示词模板（支持 {{var}}）
    generation_config JSON        NOT NULL,              -- {temperature,top_p,max_tokens,...}
    capabilities     JSON,                               -- {knowledge_base_ids,toolset_ids,skill_ids,plugin_ids,workflow_id}
    status           VARCHAR(16)  DEFAULT 'draft',
    visibility       VARCHAR(16)  DEFAULT 'private',
    current_version  VARCHAR(32),
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_agent (tenant_id, agent_id),
    KEY idx_status (tenant_id, status),
    KEY idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ② 不可变版本快照（发布时插入，永不变更，支持回滚与 Diff）
CREATE TABLE agent_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id     VARCHAR(64)  NOT NULL,
    version      VARCHAR(32)  NOT NULL,
    snapshot     JSON         NOT NULL,                -- 全量配置快照（含 prompt_hash）
    prompt_hash  VARCHAR(64),
    released_by  VARCHAR(64),
    released_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_version (agent_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ③ 会话表（需求2：会话历史与上下文管理）
CREATE TABLE session_def (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    agent_id     VARCHAR(64),
    user_id      VARCHAR(64),
    title        VARCHAR(500),
    status       VARCHAR(16)  DEFAULT 'active',        -- active / archived / cleared
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (session_id),
    KEY idx_agent (tenant_id, agent_id, updated_at),
    KEY idx_user (tenant_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ④ 消息表（原文落 JSON 列，轮次+序列）
CREATE TABLE message (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    message_id   VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    run_id       VARCHAR(64),
    turn_no      INT          NOT NULL DEFAULT 1,       -- 轮次
    seq_no       INT          NOT NULL DEFAULT 1,       -- 轮次内序号
    role         VARCHAR(16)  NOT NULL,                 -- system/user/assistant/tool
    content      JSON,                                  -- parts[]: text/image/audio/file/tool_result
    model        VARCHAR(64),
    `usage`      JSON,                                  -- {prompt_tokens, completion_tokens, cost}
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message (message_id),
    KEY idx_session (session_id, turn_no, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;