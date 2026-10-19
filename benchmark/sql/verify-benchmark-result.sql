-- ============================================================================
-- 压测结果核对：压完后跑这条，判断"有没有超卖/丢更新"
--
-- 用法：
--   mysql -uroot -p qihang-erp < benchmark/sql/verify-benchmark-result.sql
--
-- 判读：
--   * quantity / available_quantity 任一为负 → 超卖（严重缺陷）
--   * 流水净额 != 初始值 - 当前值 → 流水与库存不同事务（严重缺陷）
--   * 流水条数 > 成功请求数 → 重复记账（幂等失效）
-- ============================================================================

SELECT
    inv.id                                   AS 库存ID,
    inv.sku_code                             AS SKU,
    inv.quantity                             AS 当前总库存,
    inv.available_quantity                   AS 当前可用,
    inv.locked_quantity                      AS 当前锁定,
    CASE WHEN inv.quantity < 0 OR inv.available_quantity < 0 THEN '❌ 超卖' ELSE '✅ 正常' END AS 库存判定,
    (SELECT COUNT(*) FROM o_goods_inventory_operation op
      WHERE op.inventory_detail_id = inv.id) AS 流水条数,
    (SELECT COALESCE(SUM(CASE
                            WHEN op.type = 1 THEN op.quantity
                            WHEN op.type = 2 THEN -op.quantity
                            WHEN op.type = 5 THEN op.quantity
                            ELSE 0 END), 0)
       FROM o_goods_inventory_operation op
      WHERE op.inventory_detail_id = inv.id AND op.status = 1) AS 流水净额,
    10000000 - inv.quantity                  AS 实际减少量,
    CASE
        WHEN (SELECT COALESCE(SUM(CASE
                                      WHEN op.type = 1 THEN op.quantity
                                      WHEN op.type = 2 THEN -op.quantity
                                      WHEN op.type = 5 THEN op.quantity
                                      ELSE 0 END), 0)
                FROM o_goods_inventory_operation op
               WHERE op.inventory_detail_id = inv.id AND op.status = 1)
             = -(10000000 - inv.quantity) THEN '✅ 账实一致'
        ELSE '❌ 账实不一致'
    END                                      AS 账实判定
FROM o_goods_inventory inv
WHERE inv.sku_code LIKE 'BENCH-%'
ORDER BY inv.id;

-- 幂等键是否唯一（重复键说明幂等约束缺失）
SELECT idempotency_key, COUNT(*) AS cnt
FROM o_goods_inventory_operation
WHERE idempotency_key IS NOT NULL AND idempotency_key <> ''
GROUP BY idempotency_key
HAVING COUNT(*) > 1
LIMIT 20;
