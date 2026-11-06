-- V24：消息表记录工具调用 ID（R10，2026-11-06）
--
-- 为什么必须有它：
--   框架的会话回放需要 `role=tool` 的消息带上 `tool_call_id`，否则上游会以
--   「引用了不存在的调用」400。此前 message.content 只存 {type,text}，读回来只剩纯文本，
--   原生 tool-role 回放不可用。
--
-- 为什么可空：
--   绝大多数消息是 user/assistant，没有工具调用；给它们填假值只会让数据更脏。

ALTER TABLE ap_message
    ADD COLUMN tool_call_id VARCHAR(120) NULL COMMENT '工具调用 ID（仅 role=tool 时非空）';
