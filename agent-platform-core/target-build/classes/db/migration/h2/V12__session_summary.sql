-- 中期记忆：会话早期摘要（rollup 早期轮次，续接上下文用；H2 语法与 MySQL 兼容）
ALTER TABLE session_def ADD COLUMN summary TEXT NULL;
ALTER TABLE session_def ADD COLUMN summary_turn INT DEFAULT 0;
