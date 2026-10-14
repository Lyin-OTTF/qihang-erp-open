-- V1_27_0: 为登录用户绑定商户（租户）——第2期 堵越权
-- 多租户隔离的租户ID必须来自登录态，不再允许客户端通过 X-Tenant-Id/请求参数任意指定。
-- 在 sys_user 上增加 merchant_id：一个后台用户归属一个商户。
-- 本部署为单商户ERP，存量用户默认归属商户1（admin 即商户管理员）。
-- 若后续要做 SaaS 平台视角，可再创建 merchant_id 为 NULL 的独立平台账号，由后端显式逻辑控制跨租户。

-- 1) 幂等加列
DROP PROCEDURE IF EXISTS add_sys_user_merchant_id;
DELIMITER //
CREATE PROCEDURE add_sys_user_merchant_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'sys_user'
                     AND COLUMN_NAME = 'merchant_id') THEN
        ALTER TABLE `sys_user` ADD COLUMN `merchant_id` BIGINT NULL COMMENT '归属商户(租户)ID，NULL为平台管理员' AFTER `dept_id`;
    END IF;
END //
DELIMITER ;
CALL add_sys_user_merchant_id();
DROP PROCEDURE IF EXISTS add_sys_user_merchant_id;

-- 2) 存量用户默认归属商户1（本部署为单商户ERP，admin 即商户管理员）。
--    若后续要做 SaaS 平台视角，可再创建 merchant_id 为 NULL 的独立平台账号，而非复用 admin。
UPDATE `sys_user` SET `merchant_id` = 1 WHERE `merchant_id` IS NULL;
