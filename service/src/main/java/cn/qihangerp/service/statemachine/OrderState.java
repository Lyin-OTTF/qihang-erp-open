package cn.qihangerp.service.statemachine;

/**
 * 订单状态枚举
 * 对应 ShopOrder.orderStatus 字段
 */
public enum OrderState {
    /**
     * 0 - 新订单
     */
    NEW(0, "新订单"),

    /**
     * 1 - 待发货
     */
    PENDING_SHIPMENT(1, "待发货"),

    /**
     * 2 - 已发货
     */
    SHIPPED(2, "已发货"),

    /**
     * 3 - 已完成
     */
    COMPLETED(3, "已完成"),

    /**
     * 11 - 已取消
     */
    CANCELLED(11, "已取消"),

    /**
     * 12 - 退款中
     */
    REFUNDING(12, "退款中"),

    /**
     * 13 - 已关闭
     */
    CLOSED(13, "已关闭"),

    /**
     * 21 - 待付款
     */
    PENDING_PAYMENT(21, "待付款"),

    /**
     * 22 - 锁定
     */
    LOCKED(22, "锁定"),

    /**
     * 29 - 已删除
     */
    DELETED(29, "已删除"),

    /**
     * 101 - 部分发货
     */
    PARTIAL_SHIPPED(101, "部分发货");

    private final Integer code;
    private final String description;

    OrderState(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据code获取枚举
     */
    public static OrderState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return null;
    }
}
