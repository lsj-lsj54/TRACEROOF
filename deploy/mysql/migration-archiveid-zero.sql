-- 迁移：evidence.archiveId 的「公共案例库」语义从 NULL 改为 0
-- 背景：旧版本用 archiveId 为 NULL 表示公共案例库，新版本统一用 0 表示，
--       使所有请求都能显式携带 archiveId（公共案例库传 0），并配合 ShardingSphere 按 archiveId 分表。
-- 执行前建议先备份 evidence 表。

-- 1. 回填 NULL 为 0
UPDATE evidence SET archiveId = 0 WHERE archiveId IS NULL;

-- 2. 列改为 NOT NULL DEFAULT 0
ALTER TABLE evidence MODIFY archiveId bigint NOT NULL DEFAULT 0 COMMENT '档案库 id（0 表示公共案例库，>0 表示私有/团队档案库）';

-- 3. 已存在的团队档案库分表同样回填（若有数据）并同步列定义
--    （分表名规则为 evidence_<archiveId>，可先 SHOW TABLES LIKE 'evidence_%' 确认后再执行）
-- UPDATE evidence_<archiveId> SET archiveId = 0 WHERE archiveId IS NULL;
-- ALTER TABLE evidence_<archiveId> MODIFY archiveId bigint NOT NULL DEFAULT 0;
