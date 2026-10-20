-- 消息通知（sys_notification）
--
-- 定位：**面向人的站内通知**。与相邻两张表的区别 —— 这是本表存在的全部理由：
--   log_index       ：系统**运行**日志（机器视角，答"为什么这次调用慢"）
--   sys_audit_log   ：人的**操作**审计（答"谁在什么时候改了什么"）
--   sys_notification：给用户看的**提醒**（答"有什么需要我处理"）—— 本表
--
-- 三者不可互相替代：日志与审计都是"事后追溯"，通知是"当下提醒"。
--
-- 设计要点：
--  1) **只做点对点，不做广播**：recipient_id 为 NOT NULL。
--     广播若用 "recipient_id IS NULL = 所有人" 表达，则**一行记录被多人共享**，
--     一个人标已读会让所有人都变已读（已读状态互相污染）。真要发公告，
--     应在服务层"给每个收件人各插一行"物化，而不是在表结构上省这一列。
--  2) **已读用 read_at 时间戳而非布尔**：既能判未读，又额外留下"什么时候读的"，
--     而存储成本完全一样。NULL = 未读。
--  3) 索引按**两种查法**建：收件箱列表（租户+收件人+时间倒序）、未读统计与去重
--     （租户+收件人）。两者都以 (tenant_id, recipient_id) 打头，所以第一个索引
--     的前缀已经能服务未读查询；第二个索引带上 read_at 是为了让"未读计数"
--     在收件箱很大时仍然走索引。
--  4) **不加外键**：与项目既有表一致。通知是"当时的提醒"，收件人后来被删了，
--     历史通知也应保留（审计价值同理）。
--  5) **不加 updated_at / 删除标记**：通知只经历"创建 → 标记已读 →（可选）清理"，
--     没有编辑语义；删除按保留期整段清理（由 notice:manage 控制）。
--
-- 本文件与 mysql 目录下的副本内容相同（H2 跑 MySQL 兼容模式），沿用 V11/V15/V16/V17 的既有做法。
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V19。

CREATE TABLE sys_notification (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    notification_id VARCHAR(64)   NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL DEFAULT 'default',
    -- 收件人用户 ID（点对点，见设计要点 1）
    recipient_id    VARCHAR(64)   NOT NULL,
    -- 类型：system / task / quota / security（前端按此分组与上色）
    type            VARCHAR(32)   NOT NULL DEFAULT 'system',
    -- 级别：info / warn / error（前端按此决定是否高亮）
    level           VARCHAR(16)   NOT NULL DEFAULT 'info',
    title           VARCHAR(200)  NOT NULL,
    content         VARCHAR(1000),
    -- 点击跳转的前端路径（如 /agents / /audit / /settings），空则不可点
    link            VARCHAR(300),
    -- 已读时间；NULL = 未读
    read_at         DATETIME,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_id (notification_id),
    KEY idx_notice_inbox (tenant_id, recipient_id, created_at),
    KEY idx_notice_unread (tenant_id, recipient_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
