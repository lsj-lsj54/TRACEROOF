-- 增量迁移：给已有数据库补充缩略图字段
--
-- 什么时候需要执行：
--   deploy/mysql/init.sql 只在 MySQL 数据卷为空（首次启动）时执行，
--   如果你的库已经建好了，加字段要走这里。
--
-- 执行方式：
--   docker exec -i evidence-mysql mysql -uroot -p evidence < deploy/mysql/migration-add-thumbnail.sql
--
-- 说明：这个 ALTER 不可重复执行，重复跑会报 Duplicate column name。
--       MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，重复执行前先确认字段不存在。

ALTER TABLE `evidence`
    ADD COLUMN `thumbnailUrl` varchar(512) NULL COMMENT '缩略图 url（压缩后的 webp），列表页使用'
        AFTER `url`;

-- 历史数据没有缩略图，前端会回退到 url 显示。
-- 如果想让历史数据也有缩略图，需要重新触发一次上传（压缩图由对象存储生成）。
