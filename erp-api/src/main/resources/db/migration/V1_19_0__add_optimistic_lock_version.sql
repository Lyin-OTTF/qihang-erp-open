-- 乐观锁 version 字段添加（幂等）
-- 为订单、售后、库存、采购核心表添加 version 列，用于 MyBatis-Plus @Version 乐观锁。
-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，使用 information_schema 守卫实现幂等，
-- 这样在已含 version 列的基础库上执行也不会报 "Duplicate column"。

DROP PROCEDURE IF EXISTS agent_add_version_columns;
DELIMITER //
CREATE PROCEDURE agent_add_version_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'oms_shop_order'
                     AND COLUMN_NAME = 'version') THEN
        ALTER TABLE `oms_shop_order` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'oms_shop_refund'
                     AND COLUMN_NAME = 'version') THEN
        ALTER TABLE `oms_shop_refund` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory'
                     AND COLUMN_NAME = 'version') THEN
        ALTER TABLE `o_goods_inventory` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_purchase_order'
                     AND COLUMN_NAME = 'version') THEN
        ALTER TABLE `erp_purchase_order` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
    END IF;
END //
DELIMITER ;
CALL agent_add_version_columns();
DROP PROCEDURE IF EXISTS agent_add_version_columns;
