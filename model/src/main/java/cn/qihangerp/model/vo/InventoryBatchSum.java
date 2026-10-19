package cn.qihangerp.model.vo;

import lombok.Data;

/**
 * 单个库存记录的批次数量汇总（对账用）。
 */
@Data
public class InventoryBatchSum {

    private Long inventoryId;

    /** 批次当前数量合计 */
    private Long batchQty;

    /** 批次条数 */
    private Integer batchCount;
}
