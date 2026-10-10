package cn.qihangerp.service.statemachine;

/**
 * 状态迁移校验器
 * 统一校验状态迁移合法性，并返回标准异常信息
 */
public class TransitionValidator {

    /**
     * 校验订单状态迁移
     *
     * @param machine      订单状态机
     * @param orderId      订单业务ID（用于错误提示）
     * @param currentState 当前状态码
     * @param targetState  目标状态码
     * @throws IllegalStateTransitionException 非法状态迁移时抛出
     */
    public static void validateOrder(OrderStateMachine machine, String orderId,
                                     Integer currentState, Integer targetState) {
        if (!machine.canTransition(currentState, targetState)) {
            OrderState from = OrderState.fromCode(currentState);
            OrderState to = OrderState.fromCode(targetState);
            String fromDesc = from != null ? from.getDescription() : "未知(" + currentState + ")";
            String toDesc = to != null ? to.getDescription() : "未知(" + targetState + ")";
            throw new IllegalStateTransitionException(
                    String.format("订单[%s]状态流转不合法: [%s] -> [%s]", orderId, fromDesc, toDesc)
            );
        }
    }

    /**
     * 校验售后状态迁移
     *
     * @param machine      售后状态机
     * @param afterId      售后单号（用于错误提示）
     * @param currentState 当前状态码
     * @param targetState  目标状态码
     * @throws IllegalStateTransitionException 非法状态迁移时抛出
     */
    public static void validateRefund(RefundStateMachine machine, String afterId,
                                      Integer currentState, Integer targetState) {
        if (!machine.canTransition(currentState, targetState)) {
            RefundState from = RefundState.fromCode(currentState);
            RefundState to = RefundState.fromCode(targetState);
            String fromDesc = from != null ? from.getDescription() : "未知(" + currentState + ")";
            String toDesc = to != null ? to.getDescription() : "未知(" + targetState + ")";
            throw new IllegalStateTransitionException(
                    String.format("售后单[%s]状态流转不合法: [%s] -> [%s]", afterId, fromDesc, toDesc)
            );
        }
    }

    /**
     * 非法状态迁移异常
     */
    public static class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String message) {
            super(message);
        }
    }
}
