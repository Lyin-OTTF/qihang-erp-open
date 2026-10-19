-- ============================================================================
-- 启航ERP v4.2.0 手工迁移：库存流水闭环 + 定时对账
--
-- 与 Flyway 脚本 V1_32_0__inventory_flow_and_reconcile.sql 等价，
-- 区别是本脚本带信息模式守卫，可**重复执行**，适合在已手工改过结构的库上运行。
--
-- 执行：mysql -uroot -p qihang-erp < docs/sql/migration_v4.2.0_stock_flow_reconcile.sql
-- 注意：mysql 客户端需支持 DELIMITER（命令行默认支持；部分 GUI 需切换到脚本模式）。
-- ============================================================================

USE `qihang-erp`;

DROP PROCEDURE IF EXISTS erp_stock_flow_migrate;
DELIMITER //
CREATE PROCEDURE erp_stock_flow_migrate()
BEGIN
    -- 流水表补列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND COLUMN_NAME = 'merchant_id') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD COLUMN `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商户ID（多租户）' AFTER `id`;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND COLUMN_NAME = 'before_qty') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD COLUMN `before_qty` int NULL DEFAULT NULL COMMENT '变动前总库存' AFTER `quantity`;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND COLUMN_NAME = 'after_qty') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD COLUMN `after_qty` int NULL DEFAULT NULL COMMENT '变动后总库存' AFTER `before_qty`;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND COLUMN_NAME = 'idempotency_key') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD COLUMN `idempotency_key` varchar(128) NULL DEFAULT NULL COMMENT '幂等键（业务事件唯一标识）';
    END IF;

    -- 索引
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND INDEX_NAME = 'uk_operation_idempotency') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD UNIQUE INDEX `uk_operation_idempotency` (`idempotency_key`) USING BTREE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND INDEX_NAME = 'idx_operation_inventory') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD INDEX `idx_operation_inventory` (`inventory_detail_id` ASC, `id` ASC) USING BTREE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
                     AND INDEX_NAME = 'idx_operation_merchant_time') THEN
        ALTER TABLE `o_goods_inventory_operation`
            ADD INDEX `idx_operation_merchant_time` (`merchant_id` ASC, `create_time` ASC) USING BTREE;
    END IF;
END //
DELIMITER ;

CALL erp_stock_flow_migrate();
DROP PROCEDURE IF EXISTS erp_stock_flow_migrate;

-- 差异表（幂等建表）
CREATE TABLE IF NOT EXISTS `o_stock_reconcile_diff` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL DEFAULT '' COMMENT '对账批次ID',
    `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商户ID',
    `inventory_id` bigint NOT NULL COMMENT '库存记录ID',
    `sku_id` bigint NULL DEFAULT NULL COMMENT '规格ID',
    `sku_code` varchar(50) NULL DEFAULT NULL COMMENT '规格编码',
    `goods_name` varchar(255) NULL DEFAULT NULL COMMENT '商品名',
    `warehouse_id` bigint NULL DEFAULT 0 COMMENT '仓库ID',
    `diff_type` tinyint NOT NULL COMMENT '1可用量 2批次合计 3账实 4负库存/超锁 5流水链',
    `book_qty` bigint NULL DEFAULT NULL COMMENT '账面数量（流水推算）',
    `actual_qty` bigint NULL DEFAULT NULL COMMENT '实存数量',
    `diff_qty` bigint NULL DEFAULT NULL COMMENT '差异 = actual - book',
    `expected_value` varchar(64) NULL DEFAULT NULL COMMENT '校验期望值',
    `actual_value` varchar(64) NULL DEFAULT NULL COMMENT '校验实际值',
    `status` tinyint NOT NULL DEFAULT 0 COMMENT '0待处理 1已补偿 2已忽略',
    `detail` varchar(500) NULL DEFAULT NULL COMMENT '差异说明',
    `handled_by` varchar(50) NULL DEFAULT NULL COMMENT '处理人',
    `handled_time` datetime NULL DEFAULT NULL COMMENT '处理时间',
    `handle_remark` varchar(250) NULL DEFAULT NULL COMMENT '处理备注',
    `check_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '对账时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_diff_status` (`status` ASC, `check_time` ASC) USING BTREE,
    INDEX `idx_diff_inventory` (`inventory_id` ASC) USING BTREE,
    INDEX `idx_diff_run` (`run_id` ASC) USING BTREE,
    INDEX `idx_diff_merchant` (`merchant_id` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '库存对账差异表' ROW_FORMAT = DYNAMIC;

-- 回填历史流水的商户（幂等：只更新 merchant_id=0 的行）
UPDATE `o_goods_inventory_operation` op
    JOIN `o_goods_inventory` inv ON inv.id = op.inventory_detail_id
   SET op.merchant_id = inv.merchant_id
 WHERE op.merchant_id = 0;

-- 校验
SELECT
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_goods_inventory_operation'
        AND COLUMN_NAME IN ('merchant_id', 'before_qty', 'after_qty', 'idempotency_key')) AS '流水表新增列数(应为4)',
    (SELECT COUNT(*) FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'o_stock_reconcile_diff') AS '差异表已建(应为1)';

/*
-- 回滚（谨慎）：
ALTER TABLE o_goods_inventory_operation DROP INDEX uk_operation_idempotency;
ALTER TABLE o_goods_inventory_operation DROP INDEX idx_operation_inventory;
ALTER TABLE o_goods_inventory_operation DROP INDEX idx_operation_merchant_time;
ALTER TABLE o_goods_inventory_operation
    DROP COLUMN idempotency_key, DROP COLUMN after_qty, DROP COLUMN before_qty, DROP COLUMN merchant_id;
DROP TABLE IF EXISTS o_stock_reconcile_diff;
*/
