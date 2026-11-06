-- V23：统一表前缀 ap_（D1 裁决，2026-11-06）
--
-- 为什么用「重命名」而不是直接改 V1~V22：
--   Flyway 对已执行过的脚本做 checksum 校验，改动历史脚本会让所有既有环境**启动即失败**
--   （Validate failed: checksum mismatch）。所以保留历史脚本原样，新增一条迁移把表改名；
--   新环境等价于「先建后改名」，最终 schema 一致。
--
-- 为什么要前缀：
--   框架/平台要能被嵌进宿主库，`message`、`document`、`chunk` 这类通用名必然撞车。
--   加前缀后，宿主即使已有同名表也不冲突。

RENAME TABLE
    agent_def                TO ap_agent_def,
    agent_plugin             TO ap_agent_plugin,
    agent_version            TO ap_agent_version,
    chunk                    TO ap_chunk,
    diagnosis_report         TO ap_diagnosis_report,
    diagnosis_rule           TO ap_diagnosis_rule,
    document                 TO ap_document,
    file_asset               TO ap_file_asset,
    knowledge_base           TO ap_knowledge_base,
    log_index                TO ap_log_index,
    message                  TO ap_message,
    model_config             TO ap_model_config,
    plugin_def               TO ap_plugin_def,
    plugin_version           TO ap_plugin_version,
    prompt_optimization      TO ap_prompt_optimization,
    prompt_rule              TO ap_prompt_rule,
    session_def              TO ap_session_def,
    skill_def                TO ap_skill_def,
    sys_audit_log            TO ap_sys_audit_log,
    sys_dict_item            TO ap_sys_dict_item,
    sys_dict_type            TO ap_sys_dict_type,
    sys_notification         TO ap_sys_notification,
    sys_permission           TO ap_sys_permission,
    sys_role                 TO ap_sys_role,
    sys_role_permission      TO ap_sys_role_permission,
    sys_tool_approval        TO ap_sys_tool_approval,
    sys_user                 TO ap_sys_user,
    sys_user_fact            TO ap_sys_user_fact,
    sys_user_fact_candidate  TO ap_sys_user_fact_candidate,
    sys_user_role            TO ap_sys_user_role,
    tenant_quota             TO ap_tenant_quota,
    tool_def                 TO ap_tool_def,
    tool_registration        TO ap_tool_registration,
    workflow_def             TO ap_workflow_def;
