-- 创建「只对 evidence 库有权限」的应用账号（可选加固项）
--
-- 为什么建议做：
--   默认情况下应用是用 root 连数据库的。一旦应用被拿下（SQL 注入、依赖漏洞等），
--   攻击者就拿到了整个 MySQL 实例的最高权限，能读所有库、能改账号。
--   换成只对 evidence 库有 CRUD 权限的账号，影响面就限制在本项目的库内。
--
-- 使用步骤：
--   1. 把下面的 CHANGE_ME_STRONG_PASSWORD 换成一个独立的强密码（不要和 root 密码相同）；
--   2. 执行：
--        docker exec -i evidence-mysql mysql -uroot -p < deploy/mysql/create-app-user.sql
--   3. 在 deploy/.env 里填上：
--        MYSQL_USERNAME=evidence_app
--        MYSQL_APP_PASSWORD=<你上面设的那个密码>
--   4. 重新发布：bash deploy/deploy.sh
--
-- 说明：这里只授予 evidence 库的增删改查权限，不给 DDL、不给其它库。
--      以后如果要在库里加表/加字段，仍用 root 执行迁移脚本。

CREATE USER IF NOT EXISTS 'evidence_app'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE ON `evidence`.* TO 'evidence_app'@'%';

FLUSH PRIVILEGES;

-- 验证（应只看到 evidence 库的权限）：
--   SHOW GRANTS FOR 'evidence_app'@'%';
