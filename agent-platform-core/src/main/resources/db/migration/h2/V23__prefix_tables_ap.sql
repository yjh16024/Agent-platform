-- V23：统一表前缀 ap_（D1 裁决，2026-11-06）
--
-- H2 不支持 MySQL 的多表 RENAME TABLE，改为逐条 ALTER TABLE ... RENAME TO。
-- 与 mysql/V23 语义一致：只改名，不动结构与数据。

ALTER TABLE agent_def               RENAME TO ap_agent_def;
ALTER TABLE agent_plugin            RENAME TO ap_agent_plugin;
ALTER TABLE agent_version           RENAME TO ap_agent_version;
ALTER TABLE chunk                   RENAME TO ap_chunk;
ALTER TABLE diagnosis_report        RENAME TO ap_diagnosis_report;
ALTER TABLE diagnosis_rule          RENAME TO ap_diagnosis_rule;
ALTER TABLE document                RENAME TO ap_document;
ALTER TABLE file_asset              RENAME TO ap_file_asset;
ALTER TABLE knowledge_base          RENAME TO ap_knowledge_base;
ALTER TABLE log_index               RENAME TO ap_log_index;
ALTER TABLE message                 RENAME TO ap_message;
ALTER TABLE model_config            RENAME TO ap_model_config;
ALTER TABLE plugin_def              RENAME TO ap_plugin_def;
ALTER TABLE plugin_version          RENAME TO ap_plugin_version;
ALTER TABLE prompt_optimization     RENAME TO ap_prompt_optimization;
ALTER TABLE prompt_rule             RENAME TO ap_prompt_rule;
ALTER TABLE session_def             RENAME TO ap_session_def;
ALTER TABLE skill_def               RENAME TO ap_skill_def;
ALTER TABLE sys_audit_log           RENAME TO ap_sys_audit_log;
ALTER TABLE sys_dict_item           RENAME TO ap_sys_dict_item;
ALTER TABLE sys_dict_type           RENAME TO ap_sys_dict_type;
ALTER TABLE sys_notification        RENAME TO ap_sys_notification;
ALTER TABLE sys_permission          RENAME TO ap_sys_permission;
ALTER TABLE sys_role                RENAME TO ap_sys_role;
ALTER TABLE sys_role_permission     RENAME TO ap_sys_role_permission;
ALTER TABLE sys_tool_approval       RENAME TO ap_sys_tool_approval;
ALTER TABLE sys_user                RENAME TO ap_sys_user;
ALTER TABLE sys_user_fact           RENAME TO ap_sys_user_fact;
ALTER TABLE sys_user_fact_candidate RENAME TO ap_sys_user_fact_candidate;
ALTER TABLE sys_user_role           RENAME TO ap_sys_user_role;
ALTER TABLE tenant_quota            RENAME TO ap_tenant_quota;
ALTER TABLE tool_def                RENAME TO ap_tool_def;
ALTER TABLE tool_registration       RENAME TO ap_tool_registration;
ALTER TABLE workflow_def            RENAME TO ap_workflow_def;
