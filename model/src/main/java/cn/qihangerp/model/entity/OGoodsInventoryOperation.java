package cn.qihangerp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存操作流水
 * 对应表 o_goods_inventory_operation
 * 记录每次库存变动（扣减/增加/锁定/解锁/调整），与库存更新同事务写入
 */
@Data
@TableName("o_goods_inventory_operation")
public class OGoodsInventoryOperation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商户ID（多租户隔离与按商户对账） */
    private Long merchantId;

    private Long goodsId;
    private String goodsNum;
    private Long skuId;
    private String skuCode;
    private Long batchId;
    private String batchNum;
    /** 操作类型：1-入库 2-出库 3-锁定 4-解锁 5-调整 */
    private Integer type;
    private Long inventoryDetailId;
    /** 变动数量（正数；type=5 调整时可为负） */
    private Integer quantity;
    /** 变动前总库存（链式校验用；历史数据为 null） */
    private Integer beforeQty;
    /** 变动后总库存（应等于库存表 quantity；历史数据为 null） */
    private Integer afterQty;
    /** 幂等键：业务事件唯一标识 */
    private String idempotencyKey;
    private Integer lockedQuantity;
    private Double price;
    /** 业务类型：10-采购入库 20-采购退货 30-退货入库 40-订单出库 50-盘点 55-报损 60-对账调整 90-其他 */
    private Integer bizType;
    private Long bizId;
    private String bizNum;
    private Long bizItemId;
    /** 状态：1-成功 0-失败 */
    private Integer status;
    private String remark;
    private Long warehouseId;
    private Long positionId;
    private LocalDateTime createTime;
    private String createBy;
    private LocalDateTime updateTime;
    private String updateBy;
}
