package cn.qihangerp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频号小店退款
 * @TableName oms_shop_refund
 */
@TableName(value ="oms_shop_refund")
@Data
public class ShopRefund {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 商户id
     */
    private Long merchantId;

    /**
     * 店铺id
     */
    private Long shopId;

    /**
     * 店铺类型
     */
    private Integer shopType;

    /**
     * 售后单号
     */
    private String afterId;

    /**
     * 售后类型。(1-售前退款(取消订单) 10-退货 20-换货 30-维修 40-上门服务 80-补发商品 90-补款 91-返现 11-仅退款)
     */
    private Integer type;

    /**
     * 售后状态 0：售后申请 1：售后关闭，2：售后处理中，3：退款中，4： 售后成功，5：待用户处理，6：待买家发货，8：平台处理中
     */
    private Integer status;

    /**
     * 订单号，该字段可用于获取订单
     */
    private String orderId;

    /**
     * 订单金额
     */
    private Integer orderAmount;

    /**
     * 商品spuid
     */
    private String productId;

    /**
     * 商品名称
     */
    private String goodsName;

    /**
     * 商品图片
     */
    private String goodsImage;

    /**
     * 商品skuid
     */
    private String skuId;
    private String skuName;

    /**
     * 售后数量
     */
    private Integer count;
    /**
     * 售出数量
     */
    private Integer sellCount;

    /**
     * 标明售后单退款直接原因, 枚举值参考 RefundReason
     */
    private Integer refundReason;

    /**
     * 退款金额（分）
     */
    private Integer refundAmount;

    /**
     * 快递单号
     */
    private String returnWaybillId;

    /**
     * 物流公司id
     */
    private String returnDeliveryId;

    /**
     * 物流公司名称
     */
    private String returnDeliveryName;

    /**
     * 平台退款申请时间
     */
    private Long createTime;

    /**
     * 平台退款更新时间
     */
    private Long updateTime;

    /**
     * 退款原因
     */
    private String reason;

    /**
     * 退款原因解释
     */
    private String reasonText;

    /**
     * 确认状态1已确认0未确认
     */
    private Integer confirmStatus;

    /**
     * 确认时间
     */
    private LocalDateTime confirmTime;

    /**
     * 订单发货状态 0:未发货， 1:已发货（包含：已发货，已揽收）
     */
    private Integer orderShipStatus;

    /**
     * 0-未勾选 1-消费者选择的收货状态为未收到货 2-消费者选择的收货状态为已收到货
     */
    private Integer userShipStatus;
    /**
     * 退款状态；1-待退款;2-退款中;3-退款成功;4-退款失败;5-追缴成功;
     */
    private Integer refundStatus;

    /**
     * 1纠纷退款 0非纠纷退款
     */
    private Integer disputeRefundStatus;

    /**
     * 系统创建时间
     */
    private LocalDateTime createOn;

    /**
     * 系统更新时间
     */
    private LocalDateTime updateOn;

    /**
     * 订单状态：CLOSE已关闭、CANCEL已取消、DELETED已删除、UNPAID未付款、PART_PAID部分付款、NOT_SHIPPED未发货、PART_SHIPPED部分发货、SHIPPED已发货、REJECTED已拒收、BILL_SHIPPED、已寄票、BILL_COMPLETE已收票、PAUSE暂停、LOCKED锁定、COMPLETE已完成
     */
    private String orderStatus;

    /**
     * 退款阶段, ON_SALE售中、AFTER_SALE售后
     */
    private String refundPhase;


    /**
     * 退款说明
     */
    private String remark;

    /**
     * 平台售后状态编码
     */
    private String statusCode;

    /**
     * 平台售后状态描述
     */
    private String statusName;

    /**
     * 平台子订单id
     */
    private String subOrderId;

    /**
     * 商家编码
     */
    private String outerId;

    /**
     * 单价
     */
    private Integer goodsPrice;

    /**
     * 售后商品类型：0无需处理，1退回货品、2换出货品
     */
    private Integer goodsStatus;

    /**
     * 购买的sku(换出的sku)只有换货单有值；表示订单原有商品
     */
    private String refBoughtSkuId;

    /**
     * 换货商品规格ID
     */
    private String exchangeSkuId;

    /**
     * 换货商品名称
     */
    private String exchangeGoodsName;

    /**
     * 换货商品价格
     */
    private Integer exchangeGoodsPrice;

    /**
     * 申请换货的数量
     */
    private Integer exchangeGoodsNum;

    /**
     * 平台卖家id
     */
    private String platformSellerId;
    private String platformSellerName;
    private String orderTime;
    private String refundSuccessTime;
    private Long shopOrderId;
    private Long shopOrderItemId;
    /**
     * 平台类型
     */
    private String platformType;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;
}