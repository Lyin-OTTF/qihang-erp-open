package cn.qihangerp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存操作流水
 * 对应表 o_goods_inventory_operation
 * 记录每次库存变动（扣减/增加/锁定/解锁），与库存更新同事务写入
 */
@Data
@TableName("o_goods_inventory_operation")
public class OGoodsInventoryOperation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;
    private String goodsNum;
    private Long skuId;
    private String skuCode;
    private Long batchId;
    private String batchNum;
    /** 操作类型：1-入库 2-出库 3-锁定 4-解锁 */
    private Integer type;
    private Long inventoryDetailId;
    /** 变动数量（正数） */
    private Integer quantity;
    private Integer lockedQuantity;
    private Double price;
    /** 业务类型：1-订单 2-采购 3-盘点 4-其他 */
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
