-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- V9：平台级模型配置（单行，嵌入模型绑定）
-- 说明：embedding_binding 存 {provider,model,base_url,api_key(密文)}，
--       api_key 以 AES-GCM 密文落库（ModelConfigService 负责加解密），
--       接口层永不回传明文；空值表示未配置，嵌入退回本地 Mock。
-- ============================================================

CREATE TABLE model_config (
    id                BIGINT      NOT NULL,
    embedding_binding JSON        NULL COMMENT '{provider,model,base_url,api_key(密文)}',
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 单行平台级配置（id 固定为 1）
INSERT INTO model_config (id, embedding_binding) VALUES (1, NULL);