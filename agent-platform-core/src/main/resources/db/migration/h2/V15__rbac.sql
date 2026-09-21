-- RBAC 用户与权限体系（用户管理 + 角色权限管理）
--
-- 设计要点：
--  1) 主键沿用项目约定：id BIGINT AUTO_INCREMENT 为物理主键，另设业务 ID 列
--     （user_id / role_id / perm_id，由 IdGenerator 生成，形如 user_8f3c2a1b）；
--  2) 关联表用**业务 ID**（user_id/role_id/perm_id）而非物理 id —— 与 sys_user_role 对称，
--     且权限校验时只需单表查 role_id → perm_id，不必回表 sys_permission；
--  3) sys_permission 不带 tenant_id：权限点是系统定义的全局清单（RbacPermission 枚举为唯一来源，
--     由 RbacSeeder 启动时同步入库），租户能自定义的是**角色**（sys_role 带 tenant_id）；
--  4) 审计字段 created_at/updated_at 交给数据库默认值维护，实体侧声明为
--     insertable=false, updatable=false（与 AgentDefinition 等既有实体一致）。
--
-- 本文件与 mysql 目录下的副本内容相同：H2 以 MySQL 兼容模式运行（embedded profile），
-- 沿用 V11__tool_registration 的既有做法 —— 两份同文，便于将来出现方言差异时再分叉。

CREATE TABLE sys_user (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(64)   NOT NULL,
    tenant_id     VARCHAR(64)   NOT NULL DEFAULT 'default',
    username      VARCHAR(64)   NOT NULL,
    password_hash VARCHAR(200)  NOT NULL,
    display_name  VARCHAR(100),
    email         VARCHAR(200),
    status        VARCHAR(16)   NOT NULL DEFAULT 'active',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    role_code   VARCHAR(64)  NOT NULL,
    role_name   VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    builtin     TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_id (role_id),
    UNIQUE KEY uk_tenant_role_code (tenant_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_permission (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    perm_id    VARCHAR(64)  NOT NULL,
    perm_code  VARCHAR(100) NOT NULL,
    perm_name  VARCHAR(100) NOT NULL,
    perm_group VARCHAR(64)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_id (perm_id),
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(64) NOT NULL,
    role_id    VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_permission (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    role_id    VARCHAR(64)  NOT NULL,
    perm_id    VARCHAR(64)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, perm_id),
    KEY idx_role_perm_perm (perm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
