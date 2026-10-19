-- ============================================================================
-- 压测种子数据（可重复执行）
--
-- 设计原则：
--   1. 全部使用 BENCH- 前缀与固定 ID 段（9900001+），与业务数据物理隔离；
--   2. 幂等：先删后插，可反复执行；
--   3. 库存给足（1000 万），保证压测期间失败只可能来自并发问题而非"库存不足"。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 清理旧数据
-- ---------------------------------------------------------------------------
DELETE FROM o_goods_inventory_operation WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_goods_inventory_batch     WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_goods_inventory           WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_stock_reconcile_diff      WHERE sku_code LIKE 'BENCH-%' OR inventory_id >= 9900001;

-- ---------------------------------------------------------------------------
-- 2. 库存主记录：3 个 SKU，分别用于 Java 基准 / k6 / JMeter，互不干扰
-- ---------------------------------------------------------------------------
INSERT INTO o_goods_inventory
    (id, goods_id, goods_num, sku_id, sku_code, goods_name, quantity, locked_quantity, available_quantity,
     warehouse_id, merchant_id, shop_id, stock_status, is_delete, create_time, version)
VALUES
    (9900001, 9900001, 'BENCH-GOODS-1', 9900001, 'BENCH-STOCK-A', '压测商品A-Java基准', 10000000, 0, 10000000, 1, 0, 0, 1, 0, NOW(), 1),
    (9900002, 9900002, 'BENCH-GOODS-2', 9900002, 'BENCH-STOCK-B', '压测商品B-k6',        10000000, 0, 10000000, 1, 0, 0, 1, 0, NOW(), 1),
    (9900003, 9900003, 'BENCH-GOODS-3', 9900003, 'BENCH-STOCK-C', '压测商品C-JMeter',    10000000, 0, 10000000, 1, 0, 0, 1, 0, NOW(), 1);

-- ---------------------------------------------------------------------------
-- 3. 批次记录（与总库存对齐，避免对账把压测数据标记为"批次不一致"）
-- ---------------------------------------------------------------------------
INSERT INTO o_goods_inventory_batch
    (inventory_id, batch_num, origin_qty, current_qty, pur_price, sku_id, goods_id, sku_code,
     warehouse_id, merchant_id, shop_id, stock_status, inventory_mode, create_time)
VALUES
    (9900001, 'BENCH-A', 10000000, 10000000, 0, 9900001, 9900001, 'BENCH-STOCK-A', 1, 0, 0, 1, 0, NOW()),
    (9900002, 'BENCH-B', 10000000, 10000000, 0, 9900002, 9900002, 'BENCH-STOCK-B', 1, 0, 0, 1, 0, NOW()),
    (9900003, 'BENCH-C', 10000000, 10000000, 0, 9900003, 9900003, 'BENCH-STOCK-C', 1, 0, 0, 1, 0, NOW());

SELECT '压测数据已就绪：' AS msg, id, sku_code, quantity, available_quantity
FROM o_goods_inventory WHERE sku_code LIKE 'BENCH-%' ORDER BY id;
