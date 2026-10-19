package cn.qihangerp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存对账差异
 * 对应表 o_stock_reconcile_diff
 *
 * <p>由定时对账任务生成：每条记录代表一次"账实不符"，是告警内容与人工补偿的凭证。
 * 补偿采用"以实存为准、补流水追平账面"的方式，不直接改动物理库存。</p>
 */
@Data
@TableName("o_stock_reconcile_diff")
public class StockReconcileDiff {

    /** 可用量不一致：available_quantity != quantity - locked_quantity */
    public static final int TYPE_AVAILABLE_MISMATCH = 1;
    /** 批次合计不一致：Σ batch.current_qty != quantity */
    public static final int TYPE_BATCH_MISMATCH = 2;
    /** 账实不一致：流水末笔 after_qty != quantity */
    public static final int TYPE_BOOK_ACTUAL_MISMATCH = 3;
    /** 负库存 / 超锁 */
    public static final int TYPE_NEGATIVE_OR_OVERLOCK = 4;
    /** 流水链断裂：Σ净额 != 末笔after - 首笔before */
    public static final int TYPE_FLOW_CHAIN_BROKEN = 5;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_COMPENSATED = 1;
    public static final int STATUS_IGNORED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对账批次ID（一次任务一个UUID） */
    private String runId;

    private Long merchantId;
    private Long inventoryId;
    private Long skuId;
    private String skuCode;
    private String goodsName;
    private Long warehouseId;

    /** 差异类型，见 TYPE_* 常量 */
    private Integer diffType;

    /** 账面数量（按流水推算） */
    private Long bookQty;
    /** 实存数量（库存表 quantity） */
    private Long actualQty;
    /** 差异数量 = actual - book */
    private Long diffQty;

    /** 校验期望值（字符串，便于记录非数量型差异） */
    private String expectedValue;
    /** 校验实际值 */
    private String actualValue;

    /** 0待处理 1已确认补偿 2已忽略 */
    private Integer status;
    private String detail;

    private String handledBy;
    private LocalDateTime handledTime;
    private String handleRemark;

    private LocalDateTime checkTime;
}
