-- 增量迁移：给已有数据库补充影像主色调字段（「按颜色查图」功能依赖）
--
-- 什么时候需要执行：
--   deploy/mysql/init.sql 只在 MySQL 数据卷为空（首次启动）时执行；
--   如果你的库已经建好了，补字段要走这里。
--
-- 执行方式：
--   docker exec -i evidence-mysql mysql -uroot -p evidence < deploy/mysql/migration-add-evidence-color.sql
--
-- 说明：
--   1. 这个 ALTER 不可重复执行，重复跑会报 Duplicate column name
--      （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，执行前先确认字段不存在）。
--   2. 不执行本脚本的直接后果：Evidence 实体已声明 picColor 系列字段，
--      MyBatis-Plus 的查询会带上这些列，库里没有就会报 Unknown column，
--      首页影像会全部加载不出来（接口 500）。
--   3. 历史影像的这些字段为空，按颜色查询时会自动排除；
--      重新上传或重新抓取后才会回填主色调（由腾讯云数据万象 imageAve 接口返回）。

ALTER TABLE `evidence`
    ADD COLUMN `picColor`  varchar(7)       NULL COMMENT '主色调（腾讯云 imageAve 返回，形如 #736246）',
    ADD COLUMN `picColorR` tinyint unsigned NULL COMMENT '主色调 R 分量（0-255）',
    ADD COLUMN `picColorG` tinyint unsigned NULL COMMENT '主色调 G 分量（0-255）',
    ADD COLUMN `picColorB` tinyint unsigned NULL COMMENT '主色调 B 分量（0-255）';
