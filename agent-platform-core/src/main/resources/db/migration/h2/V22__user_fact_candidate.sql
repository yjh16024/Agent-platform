-- 长期记忆：**待确认**的自动抽取画像（sys_user_fact_candidate）
--
-- 定位：为 docs/roadmap.md 的 D1「用户画像自动抽取」提供落地表。
--
-- 为什么**另建一张表**而不是给 sys_user_fact 加 status 列：
--   1) `sys_user_fact` 的语义是「**用户已经确认过的**画像」，而那张表的内容会被
--      UserFactService.render() 直接注入系统提示词。若把待确认项混进去，
--      就必须在 render 的查询路径上多加一个过滤条件 —— 一旦哪次漏加，
--      **未经用户确认的系统推测就会被当成事实注入对话**，而这是本功能最不能出的错。
--      分成两张表后，render 读的那张表天然只含已确认项，**结构上不可能出错**。
--   2) 待确认项的生命周期也不同：可能被采纳（搬到 sys_user_fact）或被忽略（留在本表打标记），
--      与"正式画像"的增删改不是一回事。
--
-- 设计要点：
--  1) **UNIQUE (tenant_id, user_id, fact_key)**：同一用户同一个键只保留**一条**候选。
--     重复抽取同一件事时走 upsert（覆盖值与来源），而不是堆出一串待办让用户审到烦。
--     这也是"确认成本可控"的前提 —— 用户看到的永远是最新值，不是历史堆积。
--  2) **rejected_at 而非物理删除**：用户点了「忽略」之后必须**记住**这次拒绝，
--     否则下一轮对话又会把同样的东西抽出来重新打扰 —— 那是最招人烦的体验。
--     抽取时跳过 `rejected_at IS NOT NULL` 的键。
--  3) **可追溯**：记下抽取自哪个会话、由哪个模型抽取。用户质疑"你凭什么这么记我"时，
--     要能答得出来；排查"为什么抽出这条"时也不必猜。
--  4) **不做物理外键**：与项目既有表一致。
--  5) **updated_at 由 DB 维护**：ON UPDATE CURRENT_TIMESTAMP，JPA 侧标 updatable=false。
--
-- ⚠️ 隐私：本表内容**尚未**进入提示词，但一旦用户采纳就会进。因此同样严格按
--   (tenant_id, user_id) 隔离，并支持一键清除（UserFactCandidateService.clearAll）。
--
-- 本文件与 h2 目录下的副本内容相同（H2 跑 MySQL 兼容模式），沿用 V11/V15~V19 的既有做法。
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V23。

CREATE TABLE sys_user_fact_candidate (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    candidate_id      VARCHAR(64)   NOT NULL,
    tenant_id         VARCHAR(64)   NOT NULL DEFAULT 'default',
    -- 归属用户（业务 ID，对应 sys_user.user_id）
    user_id           VARCHAR(64)   NOT NULL,
    -- 与 sys_user_fact.fact_key 同义（如「职业」「常用语言」）
    fact_key          VARCHAR(100)  NOT NULL,
    fact_value        VARCHAR(1000) NOT NULL,
    -- preference(偏好) / background(背景) / goal(目标) / other
    category          VARCHAR(32)   NOT NULL DEFAULT 'other',
    -- 抽取来源：哪个会话、由哪个模型抽取（可追溯，要点 3）
    source_session_id VARCHAR(64)   NULL,
    extracted_by      VARCHAR(120)  NULL,
    -- 用户点过「忽略」的时间；NULL = 待确认（要点 2）
    rejected_at       DATETIME      NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fact_candidate_id (candidate_id),
    -- 同一用户同一键只留一条（要点 1）
    UNIQUE KEY uk_fact_candidate_key (tenant_id, user_id, fact_key),
    -- 列表页按 (tenant, user) 取，并只显示待确认的
    KEY idx_fact_candidate_owner (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
