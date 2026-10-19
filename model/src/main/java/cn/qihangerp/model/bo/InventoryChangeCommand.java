package cn.qihangerp.model.bo;

import lombok.Data;

/**
 * 库存变动指令。
 *
 * <p>把"扣多少"和"为什么扣"绑定在同一个对象里：库存原子更新与流水写入使用同一份指令，
 * 流水因此天然带有业务单号、租户、操作人与幂等键，具备可追溯与可重放能力。</p>
 *
 * <p>与旧接口 {@code deductStock(Long inventoryId, Integer quantity)} 的差别：
 * 旧接口只能记一条"扣减库存"的裸流水（bizType 硬编码为"其他"、bizId=0），
 * 对账时无法定位到订单，也无法防止重试重复记账。</p>
 *
 * @author qihang
 */
@Data
public class InventoryChangeCommand {

    /** 变动类型：1入库 2出库 3锁定 4解锁 5调整（调整的 quantity 可为负） */
    public static final int TYPE_IN = 1;
    public static final int TYPE_OUT = 2;
    public static final int TYPE_LOCK = 3;
    public static final int TYPE_UNLOCK = 4;
    public static final int TYPE_ADJUST = 5;

    /** 业务类型：与 o_goods_inventory_operation.biz_type 注释保持一致 */
    public static final int BIZ_PURCHASE_IN = 10;
    public static final int BIZ_PURCHASE_RETURN = 20;
    public static final int BIZ_RETURN_IN = 30;
    public static final int BIZ_ORDER_OUT = 40;
    public static final int BIZ_STOCKTAKING = 50;
    public static final int BIZ_DAMAGE_OUT = 55;
    public static final int BIZ_RECONCILE_ADJUST = 60;
    public static final int BIZ_OTHER = 90;

    /** 库存记录ID（o_goods_inventory.id），必填 */
    private Long inventoryId;

    /** 库存批次ID，可空 */
    private Long batchId;

    /** 批次号，可空 */
    private String batchNum;

    /** 变动数量：type=1/2/3/4 时必须为正数；type=5（调整）可为负数 */
    private Integer quantity;

    /** 变动类型，见 TYPE_* 常量 */
    private Integer type;

    /** 业务类型，见 BIZ_* 常量 */
    private Integer bizType = BIZ_OTHER;

    /** 业务单ID（订单/入库单/出库单主键） */
    private Long bizId;

    /** 业务单号（便于人工排查，不参与计算） */
    private String bizNum;

    /** 业务单明细ID */
    private Long bizItemId;

    /**
     * 幂等键：同一业务事件必须使用同一个键。
     * 建议格式 "{动作}:{业务明细ID}:{累计序号}"，例如 OUT_ITEM:1024:3。
     * 唯一索引冲突时整笔事务回滚，保证"库存只动一次、流水只有一条"。
     */
    private String idempotencyKey;

    /** 商户ID（为空时取库存记录上的 merchantId） */
    private Long merchantId;

    /** 仓库ID（为空时取库存记录上的 warehouseId） */
    private Long warehouseId;

    /** 仓位ID */
    private Long positionId;

    /** 操作人 */
    private String operator;

    /** 单价（采购入库价 / 出库成本价），可空 */
    private Double price;

    /** 备注 */
    private String remark;

    /**
     * 构造一个最小指令（兼容旧调用：只有库存ID和数量）。
     */
    public static InventoryChangeCommand of(Long inventoryId, Integer quantity, int type) {
        InventoryChangeCommand cmd = new InventoryChangeCommand();
        cmd.setInventoryId(inventoryId);
        cmd.setQuantity(quantity);
        cmd.setType(type);
        return cmd;
    }

    /**
     * 参数自检。返回错误描述，null 表示通过。
     */
    public String validate() {
        if (inventoryId == null) {
            return "缺少库存ID";
        }
        if (quantity == null || quantity == 0) {
            return "变动数量不能为空或0";
        }
        if (type == null) {
            return "缺少变动类型";
        }
        if (type >= TYPE_IN && type <= TYPE_UNLOCK && quantity < 0) {
            return "入库/出库/锁定/解锁的数量必须为正数";
        }
        if (type < TYPE_IN || type > TYPE_ADJUST) {
            return "未知的变动类型：" + type;
        }
        return null;
    }
}
