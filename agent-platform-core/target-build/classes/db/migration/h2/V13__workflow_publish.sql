-- 工作流发布：保存已发布快照，实现「草稿可继续编辑 / 线上跑已发布版本」的隔离与一键回滚
ALTER TABLE workflow_def ADD COLUMN published_definition JSON NULL;
ALTER TABLE workflow_def ADD COLUMN published_version VARCHAR(32) NULL;
ALTER TABLE workflow_def ADD COLUMN published_at DATETIME NULL;
