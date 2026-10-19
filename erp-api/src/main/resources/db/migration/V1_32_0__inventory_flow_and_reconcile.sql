-- ============================================================================
-- 库存流水闭环 + 定时对账（Flyway V1_32_0）
--
-- 背景：
--   1) o_goods_inventory_operation 只有"发生了变动"的信息，缺少租户、变动前后余额、
--      幂等键，导致流水不可按商户隔离、不可追溯、重试会重复记账；
--   2) 全库无对账表，账（流水）与实（库存表）无任何自动比对机制。
--
-- 本脚本做两件事：
--   A. 给流水表补 4 个列（merchant_id / before_qty / after_qty / idempotency_key）
--   B. 新建对账差异表 o_stock_reconcile_diff
--
-- 说明：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，本脚本按"只执行一次"设计
--      （Flyway 保证）。若在已手工改过结构的库上执行，请先核对列是否已存在，
--      或改用 docs/sql/migration_v4.2.0_stock_flow_reconcile.sql（带守卫）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. 流水表补列
-- ---------------------------------------------------------------------------

ALTER TABLE `o_goods_inventory_operation`
    ADD COLUMN `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商户ID（多租户，0=历史数据未回填）' AFTER `id`,
    ADD COLUMN `before_qty` int NULL DEFAULT NULL COMMENT '本次变动前的总库存（用于账实链式校验，历史数据为NULL）' AFTER `quantity`,
    ADD COLUMN `after_qty` int NULL DEFAULT NULL COMMENT '本次变动后的总库存（与库存表同事务写入，应等于quantity）' AFTER `before_qty`,
    ADD COLUMN `idempotency_key` varchar(128) NULL DEFAULT NULL COMMENT '幂等键：业务事件唯一标识，重复提交不重复记账';

-- 幂等键唯一索引：NULL 不参与唯一性约束，历史数据可共存
ALTER TABLE `o_goods_inventory_operation`
    ADD UNIQUE INDEX `uk_operation_idempotency` (`idempotency_key`) USING BTREE;

-- 对账按库存维度聚合流水
ALTER TABLE `o_goods_inventory_operation`
    ADD INDEX `idx_operation_inventory` (`inventory_detail_id` ASC, `id` ASC) USING BTREE;

-- 多租户查询 + 按时间窗口对账
ALTER TABLE `o_goods_inventory_operation`
    ADD INDEX `idx_operation_merchant_time` (`merchant_id` ASC, `create_time` ASC) USING BTREE;

-- ---------------------------------------------------------------------------
-- B. 对账差异表
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `o_stock_reconcile_diff` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL DEFAULT '' COMMENT '对账批次ID（一次任务一个UUID，便于按批次追溯/回滚）',
    `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商户ID',
    `inventory_id` bigint NOT NULL COMMENT '库存记录ID（o_goods_inventory.id）',
    `sku_id` bigint NULL DEFAULT NULL COMMENT '规格ID',
    `sku_code` varchar(50) NULL DEFAULT NULL COMMENT '规格编码',
    `goods_name` varchar(255) NULL DEFAULT NULL COMMENT '商品名',
    `warehouse_id` bigint NULL DEFAULT 0 COMMENT '仓库ID',
    `diff_type` tinyint NOT NULL COMMENT '差异类型：1可用量不一致 2批次合计不一致 3账实不一致(流水末笔after与库存quantity不等) 4负库存/超锁 5流水链断裂',
    `book_qty` bigint NULL DEFAULT NULL COMMENT '账面数量（按流水推算）',
    `actual_qty` bigint NULL DEFAULT NULL COMMENT '实存数量（库存表 quantity）',
    `diff_qty` bigint NULL DEFAULT NULL COMMENT '差异数量 = actual - book',
    `expected_value` varchar(64) NULL DEFAULT NULL COMMENT '校验期望值（如 quantity-locked_quantity、批次合计）',
    `actual_value` varchar(64) NULL DEFAULT NULL COMMENT '校验实际值',
    `status` tinyint NOT NULL DEFAULT 0 COMMENT '处理状态：0待处理 1已确认补偿 2已忽略',
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
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '库存对账差异表（账实差异记录与补偿凭证）' ROW_FORMAT = DYNAMIC;

-- ---------------------------------------------------------------------------
-- C. 存量数据回填（可选，建议业务低峰执行）
-- ---------------------------------------------------------------------------
-- 历史流水没有 merchant_id / before_qty / after_qty：
--   1) merchant_id 可从库存表回填，使多租户查询在历史数据上也可用：
--      UPDATE o_goods_inventory_operation op
--        JOIN o_goods_inventory inv ON inv.id = op.inventory_detail_id
--         SET op.merchant_id = inv.merchant_id
--       WHERE op.merchant_id = 0;
--   2) before_qty / after_qty 无法可靠重建，保持 NULL；对账逻辑对 NULL 行跳过
--      "账实链式校验"，只参与净额汇总。
