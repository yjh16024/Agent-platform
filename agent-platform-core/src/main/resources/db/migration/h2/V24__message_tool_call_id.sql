-- V24：消息表记录工具调用 ID（R10，2026-11-06）
-- 与 mysql/V24 语义一致；H2 不支持列注释，去掉 COMMENT。

ALTER TABLE ap_message ADD COLUMN tool_call_id VARCHAR(120);
