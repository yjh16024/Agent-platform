-- 工具审批：改前快照与回滚（sys_tool_approval 加两列）
--
-- 本文件与 mysql 目录下的副本内容相同（H2 以 MySQL 兼容模式运行），沿用 V12/V15~V20 的既有做法。
--
-- 定位：把"智能体改文件"从"不可逆"变成"可撤销"。
-- 这是用户敢让它动自己代码的前提 —— 没有回滚，审批放行的每一条都是单向门。
--
-- 设计要点：
--  1) **快照以「审批」为单位**，不是"每个文件留一份 .bak"。
--     理由：用户的心智是"撤销刚才那次操作"，而一次批准可能改一个文件、
--     也可能新建一个文件 —— 单文件备份表达不了"这次操作整体是什么"。
--     快照目录约定为 `{snapshot-dir}/{approvalId}/`，按相对路径镜像原文件。
--  2) **记录 snapshot_dir 而不是布尔值**：将来若把快照挪到别处（如对象存储），
--     有这一列才能定位；为空表示这次操作没有产生快照。
--  3) **rolled_back_at 用时间戳而非布尔**：既能判"是否已回滚"，
--     又留下"什么时候回滚的"。且允许为空是必要的：绝大多数记录永远不会被回滚。
--  4) 回滚有**幂等保护**：已有 rolled_back_at 的记录不允许再次回滚。
--
-- 注意：迁移一旦执行就不可修改（Flyway checksum），要改只能新建 V22。

ALTER TABLE sys_tool_approval ADD COLUMN snapshot_dir VARCHAR(500) NULL;
ALTER TABLE sys_tool_approval ADD COLUMN rolled_back_at DATETIME NULL;
