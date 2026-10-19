package cn.qihangerp.model.vo;

import lombok.Data;

/**
 * 单个库存记录的流水汇总（对账用）。
 *
 * <p>一次聚合查询拿到对账所需的全部流水指标，避免按库存逐条查流水造成 N+1。</p>
 */
@Data
public class InventoryFlowStat {

    /** 库存记录ID（o_goods_inventory.id） */
    private Long inventoryId;

    /** 流水条数 */
    private Integer flowCount;

    /** 净变动量 = Σ入库 - Σ出库 + Σ调整（锁定/解锁不改总库存） */
    private Long netQty;

    /** 首笔流水的变动前库存（历史数据可能为 null） */
    private Integer firstBefore;

    /** 末笔流水的变动后库存（历史数据可能为 null） */
    private Integer lastAfter;
}
