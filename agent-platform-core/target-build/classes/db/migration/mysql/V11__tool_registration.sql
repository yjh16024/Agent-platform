-- 工具注册持久化：解决「HTTP 热注册工具重启/重构后丢失」的问题。
-- 启动时由 ToolRegistrationService 加载 enabled=1 的记录重建 HttpApiTool。
CREATE TABLE tool_registration (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(64)   NOT NULL DEFAULT 'default',
    tool_name    VARCHAR(64)   NOT NULL,
    description  VARCHAR(500),
    endpoint     VARCHAR(1000) NOT NULL,
    method       VARCHAR(16)   NOT NULL DEFAULT 'POST',
    parameters   JSON,
    source       VARCHAR(32)   NOT NULL DEFAULT 'http',
    enabled      TINYINT(1)    NOT NULL DEFAULT 1,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_tool (tenant_id, tool_name),
    KEY idx_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
