-- 长期记忆：用户显式画像（sys_user_fact）
--
-- 定位：多层级记忆的**第三层**（见 docs/design.md 2.3）。四层分工：
--   短期  SessionRecentCache   最近 25 轮原文，Redis List，24h TTL
--   中期  session_def.summary  超 40 轮后懒 rollup 的要点摘要
--   长期  本表                 用户**显式填写**的稳定事实，跨会话、跨智能体长期有效
--   向量  vector store         历史对话按语义召回（第四层，另有实现）
--
-- 设计要点（每条都对应一次取舍）：
--  1) **键值对而非一大段文本**：`fact_key` + `fact_value`。
--     写成整段自由文本最省事，但那样无法做到「按条编辑 / 按条删除 / 按条停注」——
--     用户想改「职业」却要重写整段画像，是很糟的体验；也无法做去重。
--     代价是表多两列，换来的是 UI 能做成列表、注入时能挑着用。
--  2) **UNIQUE (tenant_id, user_id, fact_key)**：同一用户同一个 key 只允许一条。
--     写入语义因此是 **upsert**（服务层先查后改），而不是无脑 insert 出一堆重复项。
--  3) **source 区分来源**：manual（用户手填）/ auto（系统抽取）。
--     按 backlog 的决定「先做用户主动填写、自动抽取后置」，但字段现在就留 ——
--     加一个枚举值比改表便宜得多。自动抽取的内容将来要能单独筛出来给用户确认。
--  4) **不加 enabled 开关**：删除即停用。多一个布尔列就要维护「停用态是否注入」
--     「UI 怎么展示停用」两处逻辑，而用户想停用时直接删更符合直觉。
--  5) **不做物理外键**：与项目既有表一致（用户被删后历史画像保留，审计价值同理）。
--  6) **updated_at 由 DB 维护**：ON UPDATE CURRENT_TIMESTAMP，JPA 侧标 updatable=false。
--
-- ⚠️ 隐私：本表存的是**用户自己的画像**，会进系统提示词。因此：
--   ① 严格按 (tenant_id, user_id) 隔离，查询一律带这两个条件（fail-closed）；
--   ② 必须支持用户**一键清除**（见 UserFactService.clearAll）。
--
-- 本文件与 h2 目录下的副本内容相同（H2 跑 MySQL 兼容模式），沿用 V11/V15~V18 的既有做法。
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V20。

CREATE TABLE sys_user_fact (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    fact_id    VARCHAR(64)   NOT NULL,
    tenant_id  VARCHAR(64)   NOT NULL DEFAULT 'default',
    -- 归属用户（业务 ID，对应 sys_user.user_id）
    user_id    VARCHAR(64)   NOT NULL,
    -- 事实的键（如「职业」「常用语言」「称呼偏好」），同一用户内唯一
    fact_key   VARCHAR(100)  NOT NULL,
    fact_value VARCHAR(1000) NOT NULL,
    -- 前端分组用：preference(偏好) / background(背景) / goal(目标) / other
    category   VARCHAR(32)   NOT NULL DEFAULT 'other',
    -- 来源：manual(用户手填) / auto(系统抽取，当前未启用)
    source     VARCHAR(16)   NOT NULL DEFAULT 'manual',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_fact_id (fact_id),
    -- 同一用户同一 key 唯一 → 写入走 upsert（设计要点 2）
    UNIQUE KEY uk_user_fact_key (tenant_id, user_id, fact_key),
    -- 注入时按 (tenant, user) 全量取，这个索引覆盖该查询
    KEY idx_user_fact_owner (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
