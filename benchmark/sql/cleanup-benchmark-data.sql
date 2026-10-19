-- ============================================================================
-- 压测数据清理：压测结束后必须执行，避免污染定时对账的扫描范围
-- ============================================================================
DELETE FROM o_goods_inventory_operation WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_goods_inventory_batch     WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_goods_inventory           WHERE sku_code LIKE 'BENCH-%';
DELETE FROM o_stock_reconcile_diff      WHERE sku_code LIKE 'BENCH-%' OR inventory_id >= 9900001;

SELECT '压测数据已清理' AS msg;
