-- ============================================================
-- 智能体平台数据模型 · MySQL 8.4 DDL
-- V10：平台级默认「对话模型」绑定 + 日志持久化补列
-- 说明：
--   1) model_config 增加 chat_binding —— 智能体未配置模型时回退到平台默认对话模型，
--      避免「每个智能体都要重复填一遍 API Key / baseUrl」（与模型设置页职责重叠问题）。
--   2) log_index 补 stack_trace 列（日志落库需要记录异常堆栈）与时间索引，
--      支撑运行日志页按时间倒序分页、瀑布图与诊断。
-- ============================================================

ALTER TABLE model_config
    ADD COLUMN chat_binding JSON NULL COMMENT '{provider,model,base_url,api_key(密文)}'
    AFTER embedding_binding;

ALTER TABLE log_index
    ADD COLUMN stack_trace MEDIUMTEXT NULL COMMENT '异常堆栈（脱敏/截断后）'
    AFTER context;

ALTER TABLE log_index
    ADD KEY idx_tenant_time (tenant_id, timestamp);
