-- ============================================================================
-- 启航ERP v4.1.1 数据库迁移脚本
-- 功能: 为订单和售后表添加乐观锁version字段
-- 日期: 2026-10-10
-- 用途: 支持并发控制，防止订单状态竞态更新
-- ============================================================================

USE `qihang-erp`;

-- ============================================================================
-- 1. 订单相关表添加version字段
-- ============================================================================

-- 1.1 店铺订单表 (oms_shop_order)
ALTER TABLE `oms_shop_order`
ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，每次更新+1'
AFTER `update_on`;

-- 1.2 ERP订单表 (erp_o_order)
ALTER TABLE `erp_o_order`
ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，每次更新+1'
AFTER `update_on`;

-- ============================================================================
-- 2. 售后相关表添加version字段
-- ============================================================================

-- 2.1 店铺售后表 (oms_shop_refund)
ALTER TABLE `oms_shop_refund`
ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，每次更新+1'
AFTER `update_on`;

-- 2.2 ERP售后表 (erp_o_after_sale)
ALTER TABLE `erp_o_after_sale`
ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，每次更新+1'
AFTER `update_on`;

-- ============================================================================
-- 3. 库存表添加version字段 (高并发场景)
-- ============================================================================

-- 3.1 商品库存表 (erp_goods_inventory)
ALTER TABLE `erp_goods_inventory`
ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，防止库存扣减竞态'
AFTER `update_time`;

-- ============================================================================
-- 4. 验证脚本执行结果
-- ============================================================================

-- 查看表结构确认version字段已添加
SELECT
    TABLE_NAME AS '表名',
    COLUMN_NAME AS '字段名',
    COLUMN_TYPE AS '字段类型',
    COLUMN_DEFAULT AS '默认值',
    COLUMN_COMMENT AS '备注'
FROM
    information_schema.COLUMNS
WHERE
    TABLE_SCHEMA = 'qihang-erp'
    AND COLUMN_NAME = 'version'
    AND TABLE_NAME IN (
        'oms_shop_order',
        'erp_o_order',
        'oms_shop_refund',
        'erp_o_after_sale',
        'erp_goods_inventory'
    )
ORDER BY TABLE_NAME;

-- ============================================================================
-- 5. 回滚脚本 (仅在需要时执行)
-- ============================================================================

/*
-- 回滚所有version字段
ALTER TABLE `oms_shop_order` DROP COLUMN `version`;
ALTER TABLE `erp_o_order` DROP COLUMN `version`;
ALTER TABLE `oms_shop_refund` DROP COLUMN `version`;
ALTER TABLE `erp_o_after_sale` DROP COLUMN `version`;
ALTER TABLE `erp_goods_inventory` DROP COLUMN `version`;
*/

-- ============================================================================
-- 执行说明
-- ============================================================================
-- 1. 本脚本会为5张表添加version字段，默认值为1
-- 2. 对已有数据不影响，所有记录的version初始值为1
-- 3. 执行时间约5-10秒（取决于数据量）
-- 4. 建议在业务低峰期执行
-- 5. 执行前请备份数据库
-- ============================================================================
