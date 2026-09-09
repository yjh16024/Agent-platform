-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- V8：智能体模型绑定（provider/model/base_url/api_key）
-- 说明：api_key 以 AES-GCM 密文落库（ModelBindingService 负责加解密），
--       接口层永不回传明文；空值表示未配置，运行时回退到全局默认。
-- ============================================================

ALTER TABLE agent_def
    ADD COLUMN model_binding JSON NULL COMMENT '{provider,model,base_url,api_key(密文)}'
    AFTER generation_config;