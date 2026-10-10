package cn.qihangerp.service.statemachine;

/**
 * 售后状态枚举
 * 对应 ShopRefund.status 字段
 */
public enum RefundState {
    /**
     * 0 - 售后申请
     */
    APPLIED(0, "售后申请"),

    /**
     * 1 - 售后关闭
     */
    CLOSED(1, "售后关闭"),

    /**
     * 2 - 售后处理中
     */
    PROCESSING(2, "售后处理中"),

    /**
     * 3 - 退款中
     */
    REFUNDING(3, "退款中"),

    /**
     * 4 - 售后成功
     */
    SUCCESS(4, "售后成功"),

    /**
     * 5 - 待用户处理
     */
    PENDING_USER(5, "待用户处理"),

    /**
     * 6 - 待买家发货
     */
    PENDING_BUYER_SHIP(6, "待买家发货"),

    /**
     * 8 - 平台处理中
     */
    PLATFORM_PROCESSING(8, "平台处理中");

    private final Integer code;
    private final String description;

    RefundState(Integer code, String description) {
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
    public static RefundState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RefundState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return null;
    }
}
