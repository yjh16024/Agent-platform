-- 数据字典（字典类型 + 字典项）
--
-- 设计要点：
--  1) 为什么是**两张表**而不是一张：单表要靠 SELECT DISTINCT type_code 反推类型，
--     无法表达"刚建好、还没加任何项的字典类型"，管理界面（左类型 / 右字典项）也就做不出来；
--  2) 主键与业务 ID 沿用项目约定：id BIGINT AUTO_INCREMENT 为物理主键，
--     dict_type_id / dict_item_id 由 IdGenerator 生成（形如 dict_type_8f3c2a1b）；
--  3) sys_dict_item 存的是 type_code 而**不是** dict_type_id：与 sys_user_role 存业务 ID
--     的既有做法一致，查询免回表；
--  4) 带 tenant_id：各租户可自定义自己的选项。内置类型用 builtin=1 保护
--     （禁止删除、允许改标签），避免用户删掉 log_level 之类的类型导致对应页面下拉变空；
--  5) 审计字段 created_at/updated_at 交给数据库默认值维护，实体侧声明为
--     insertable=false, updatable=false（与 SysUser 等既有实体一致）。
--
-- 本文件与 mysql 目录下的副本内容相同：H2 以 MySQL 兼容模式运行（embedded profile），
-- 沿用 V11__tool_registration / V15__rbac 的既有做法 —— 两份同文，便于将来出现方言差异时再分叉。
--
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V17。

CREATE TABLE sys_dict_type (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    dict_type_id VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    type_code    VARCHAR(64)  NOT NULL,
    type_name    VARCHAR(100) NOT NULL,
    remark       VARCHAR(500),
    status       VARCHAR(16)  NOT NULL DEFAULT 'active',
    builtin      TINYINT(1)   NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type_id (dict_type_id),
    UNIQUE KEY uk_tenant_type_code (tenant_id, type_code),
    KEY idx_dict_type_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_dict_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    dict_item_id VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    type_code    VARCHAR(64)  NOT NULL,
    item_value   VARCHAR(100) NOT NULL,
    item_label   VARCHAR(100) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'active',
    remark       VARCHAR(500),
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_item_id (dict_item_id),
    UNIQUE KEY uk_tenant_type_value (tenant_id, type_code, item_value),
    KEY idx_dict_item_lookup (tenant_id, type_code, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
