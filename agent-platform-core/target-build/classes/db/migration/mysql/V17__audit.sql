-- 操作日志 / 审计（sys_audit_log）
--
-- 与运行日志（log_index，V10）的区别 —— 这是本表存在的全部理由：
--   log_index    ：**系统运行**产生的日志（agent 执行、LLM 调用、工具调用），来源是 logger 埋点；
--   sys_audit_log：**人的操作**（谁、何时、对什么、做了什么、结果如何），来源是拦截 HTTP 请求。
--   两者不可互相替代：前者答不了"这个智能体昨天是谁改的"，后者答不了"这次调用为什么慢"。
--
-- 设计要点：
--  1) **roles 存逗号分隔的字符串快照，不建关联表** —— 审计要保留"操作发生**当时**的身份"。
--     用户后来被调岗、角色被改，都不该让历史审计记录跟着变，否则审计就失去意义了。
--  2) 与既有实体约定一致：id 为自增物理主键，audit_id 为业务 ID（IdGenerator.generate("audit")）。
--  3) 索引按三种查法建：按租户+时间倒序（列表页）、按操作人（查某人做过什么）、
--     按目标（查某个对象的操作历史 —— 答辩演示"这个智能体被谁改过"就用它）。
--  4) **刻意不加 updated_at / 不加删除标记**：审计记录一旦写入就不再修改，
--     清理只能整段按时间删除（由 audit:manage 控制），不允许单条篡改。
--  5) 审计写入失败**不能连累业务**（切面用独立事务 + 吞异常），所以本表可用性要求低于业务表。
--
-- 本文件与 h2 目录下的副本内容相同（H2 跑 MySQL 兼容模式），沿用 V11/V15/V16 的既有做法。
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V18。

CREATE TABLE sys_audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    audit_id    VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    -- 操作人（token 里解析出来的；演示模式下可能为空）
    user_id     VARCHAR(64),
    username    VARCHAR(64),
    -- 操作当时的角色快照（逗号分隔），见设计要点 1
    roles       VARCHAR(200),
    -- 业务动作名（来自 @AuditLog 注解，如"删除用户"）；没注解时回落到 "METHOD /path"
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(64),
    target_id   VARCHAR(128),
    -- 请求本身
    method      VARCHAR(10)  NOT NULL,
    uri         VARCHAR(500) NOT NULL,
    -- 结果
    http_status INT,
    success     TINYINT(1)   NOT NULL DEFAULT 1,
    error_msg   VARCHAR(500),
    -- 来源与耗时
    ip          VARCHAR(64),
    user_agent  VARCHAR(300),
    duration_ms BIGINT,
    -- 补充描述（注解给的；**绝不记录请求体**，见 AuditAspect 的说明）
    detail      VARCHAR(500),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_id (audit_id),
    KEY idx_audit_tenant_time (tenant_id, created_at),
    KEY idx_audit_user (user_id, created_at),
    KEY idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
