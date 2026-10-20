-- ============================================================
-- 移除三个已废弃的内置示例插件：自动回复 / 文字转语音 / 语音识别
-- （2026-09-20）
--
-- 背景：这三个是 Phase 4 早期为了演示"插件能装上吗"而写的示例 ——
--   plugin_auto_reply / plugin_tts_azure / plugin_asr
-- 它们的规则与音频地址都是写死的 mock，平台不再需要它们；
-- 内置插件改用「自己写 @Component 实现 Plugin SPI」的方式，外部插件样板见 plugin-example 模块。
--
-- 注意：这里只清理 plugin_def 与 agent_plugin 两张表。
-- agent_def.capabilities 里可能残留这三个 pluginId，**刻意不清理**：
--   ① 双数据库（MySQL/H2）对 JSON 数组元素删除的语法差异大，写错会让迁移整体失败；
--   ② 残留 id 无副作用 —— 运行时兜底热加载找不到插件时只记 debug 日志，
--      界面「已挂载」读的是 agent_plugin 表（已清理），所以展示是干净的。
-- ============================================================

-- ① 先解绑：把挂载关系清掉
DELETE FROM agent_plugin
WHERE plugin_id IN ('plugin_auto_reply', 'plugin_tts_azure', 'plugin_asr');

-- ② 插件的版本记录（若曾写入）
DELETE FROM plugin_version
WHERE plugin_id IN ('plugin_auto_reply', 'plugin_tts_azure', 'plugin_asr');

-- ③ 插件元数据本体（限定 __platform__，避免误删租户自建的同名插件）
DELETE FROM plugin_def
WHERE tenant_id = '__platform__'
  AND plugin_id IN ('plugin_auto_reply', 'plugin_tts_azure', 'plugin_asr');
