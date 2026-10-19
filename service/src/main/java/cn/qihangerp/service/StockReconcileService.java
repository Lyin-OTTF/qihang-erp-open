package cn.qihangerp.service;

import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.vo.StockReconcileReport;

/**
 * 库存对账 Service。
 *
 * <p>"账"指库存流水（o_goods_inventory_operation），"实"指库存表（o_goods_inventory）。
 * 对账任务只做发现与记录，不擅自改库存；补偿必须由人工确认后逐条触发。</p>
 *
 * <p>五项校验：
 * <ol>
 *   <li>可用量一致性：available_quantity == quantity - locked_quantity</li>
 *   <li>批次一致性：Σ batch.current_qty == quantity（仅有批次的库存参与）</li>
 *   <li>账实一致性：最后一笔流水的 after_qty == quantity</li>
 *   <li>负库存/超锁：quantity、available_quantity 不为负，locked_quantity 不超过 quantity</li>
 *   <li>流水链连续性：Σ净额 == 末笔 after_qty - 首笔 before_qty</li>
 * </ol>
 *
 * @author qihang
 */
public interface StockReconcileService {

    /**
     * 执行全量对账（可限定商户）。
     *
     * @param merchantId 商户ID，null 表示全库
     * @return 本次对账报告（含新增差异）
     */
    StockReconcileReport reconcile(Long merchantId);

    /**
     * 执行指定库存的对账（手工触发单个 SKU 排查时使用）。
     */
    StockReconcileReport reconcileInventories(Long merchantId, java.util.Collection<Long> inventoryIds);

    /**
     * 人工确认后补偿一条差异。
     *
     * <p>补偿策略："以实存为准、补流水追平账面"，不直接扣减物理库存：
     * <ul>
     *   <li>可用量不一致 → 重算 available_quantity = quantity - locked_quantity</li>
     *   <li>批次合计不一致 → 差额按 FEFO 逆序回填到批次，使批次合计等于总库存</li>
     *   <li>账实不一致 → 写一条 type=5 的调整流水，使流水账面追平实存</li>
     *   <li>负库存/超锁 → 拒绝自动补偿，需盘点后人工处理</li>
     * </ul>
     *
     * @param diffId   差异ID
     * @param operator 处理人
     * @param remark   处理备注
     * @return 补偿说明；失败抛 {@link cn.qihangerp.common.ServiceException}
     */
    String compensate(Long diffId, String operator, String remark);

    /**
     * 忽略一条差异（已知晓、暂不处理）。
     */
    void ignore(Long diffId, String operator, String remark);

    /**
     * 查询单条库存当前的对账状态（按需排查用）。
     */
    OGoodsInventory getInventoryForCheck(Long inventoryId);

    /**
     * 分页查询对账差异。
     *
     * @param status     处理状态，null 表示全部
     * @param merchantId 商户ID，null 表示全部
     */
    cn.qihangerp.common.PageResult<cn.qihangerp.model.entity.StockReconcileDiff> queryDiffs(
            Integer status, Long merchantId, cn.qihangerp.common.PageQuery pageQuery);
}
