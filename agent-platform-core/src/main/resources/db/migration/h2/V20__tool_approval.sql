-- 工具审批（sys_tool_approval）
--
-- 本文件与 mysql 目录下的副本内容相同（H2 以 MySQL 兼容模式运行），沿用 V11/V15~V19 的既有做法。
--
-- 定位：给"能改文件 / 执行命令"这类**有副作用**的工具加一道人工闸门。
-- 没有它，写工具就等于"一个能删任何文件的盒子"—— **写能力必须与审批同时上线**。
--
-- 采用**申请-批准两步**（而非阻塞式弹窗）：
--   1) 工具执行时**只提交申请**、立即返回"等待批准"，绝不落笔写文件；
--   2) 用户在前端看到待审批项，点批准后，由 ApprovalService **直接执行**（参数已存库）。
-- 为什么不做阻塞式：那要求 HTTP/SSE 请求挂在等待中，还要处理超时、断连、并发审批等状态，
-- 而本平台主链路是"一次请求一次响应"。两步式的代价只是"用户点完批准，模型下一轮才知道"。
--
-- 设计要点：
--  1) **参数整体存 JSON 快照**：批准时按**当时提交的参数**执行，避免 TOCTOU
--     （模型第二次调用时改了参数），也不需要重放对话。
--  2) **状态机**：pending / approved / rejected / expired。expired 供清理陈年申请。
--  3) **记录 decided_by / decided_at**：审批本身要留痕（"谁批的这个删除"必须能查）。
--     与 sys_audit_log 的分工：审计记"发生了什么操作"，本表记"这个操作被谁放行的"。
--  4) **不存大结果**：result 只留摘要。
--  5) 不做物理外键：与项目既有表一致。
--
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V21。

CREATE TABLE sys_tool_approval (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    approval_id VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    -- 发起方上下文：批准后要能回到"哪个智能体/哪个会话/哪次运行"去执行与追溯
    agent_id    VARCHAR(64),
    session_id  VARCHAR(64),
    run_id      VARCHAR(64),
    -- 申请人（工具调用由当前登录用户触发）
    user_id     VARCHAR(64),
    tool_name   VARCHAR(128) NOT NULL,
    -- 待执行参数的 JSON 快照（批准时按它执行，避免 TOCTOU）
    tool_args   TEXT,
    -- 给用户看的理由/影响摘要（如"将把 src/A.java 的第 3 行改为 xxx"）
    summary     VARCHAR(500),
    -- pending / approved / rejected / expired
    status      VARCHAR(16)  NOT NULL DEFAULT 'pending',
    -- 批准执行后的结果摘要（截断）
    result      TEXT,
    error_msg   VARCHAR(1000),
    decided_by  VARCHAR(64),
    decided_at  DATETIME,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_id (approval_id),
    -- 待审批列表：按租户+状态+时间倒序（前端首屏查询正好走这个索引）
    KEY idx_approval_inbox (tenant_id, status, created_at),
    -- 按会话查（"这个会话还有几条待批"用）
    KEY idx_approval_session (tenant_id, session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
