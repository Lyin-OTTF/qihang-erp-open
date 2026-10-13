package cn.qihangerp.agent.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 状态迁移校验器。
 *
 * <p>统一校验订单和售后单的状态迁移合法性，在 AI 写工具或 Service 层
 * 更新状态前调用，防止非法跳转。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@Component
public class TransitionValidator {

    /**
     * 校验订单状态迁移
     *
     * @param fromState 当前订单状态
     * @param toState 目标订单状态
     * @param orderId 订单ID（用于日志）
     * @throws IllegalTransitionException 迁移非法时抛出
     */
    public void validateOrderTransition(Integer fromState, Integer toState, String orderId) {
        if (!OrderStateMachine.canTransition(fromState, toState)) {
            String msg = String.format("订单状态非法迁移: orderId=%s, %s(%d) → %s(%d)",
                    orderId,
                    OrderStateMachine.getStateName(fromState), fromState,
                    OrderStateMachine.getStateName(toState), toState);
            log.warn(msg);
            throw new IllegalTransitionException(msg);
        }
        log.debug("订单状态迁移校验通过: orderId={}, {}→{}", orderId,
                OrderStateMachine.getStateName(fromState), OrderStateMachine.getStateName(toState));
    }

    /**
     * 校验售后单状态迁移
     */
    public void validateRefundTransition(Integer fromState, Integer toState, String refundId) {
        if (!RefundStateMachine.canTransition(fromState, toState)) {
            String msg = String.format("售后状态非法迁移: refundId=%s, %s(%d) → %s(%d)",
                    refundId,
                    RefundStateMachine.getStateName(fromState), fromState,
                    RefundStateMachine.getStateName(toState), toState);
            log.warn(msg);
            throw new IllegalTransitionException(msg);
        }
        log.debug("售后状态迁移校验通过: refundId={}, {}→{}", refundId,
                RefundStateMachine.getStateName(fromState), RefundStateMachine.getStateName(toState));
    }

    /**
     * 非法状态迁移异常
     */
    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(String message) {
            super(message);
        }
    }
}
