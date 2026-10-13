package cn.qihangerp.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 状态迁移校验器。
 *
 * <p>统一校验订单和售后单的状态迁移合法性，在 Service 层更新状态前调用，
 * 防止非法跳转。每次校验（通过/拦截）都会发布 {@link StateTransitionEvent}，
 * 由上层监听并写入 Trace，实现状态机与监视环节的打通。</p>
 *
 * @author 状态机下沉
 * @since 2026-10-13
 */
@Slf4j
@Component
public class TransitionValidator {

    private final ApplicationEventPublisher eventPublisher;

    public TransitionValidator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 校验订单状态迁移
     *
     * @param fromState 当前订单状态
     * @param toState 目标订单状态
     * @param orderId 订单ID（用于日志/trace）
     * @throws IllegalTransitionException 迁移非法时抛出
     */
    public void validateOrderTransition(Integer fromState, Integer toState, String orderId) {
        boolean allowed = OrderStateMachine.canTransition(fromState, toState);
        publish("ORDER", orderId, fromState, toState,
                OrderStateMachine.getStateName(fromState),
                OrderStateMachine.getStateName(toState), allowed);
        if (!allowed) {
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
        boolean allowed = RefundStateMachine.canTransition(fromState, toState);
        publish("REFUND", refundId, fromState, toState,
                RefundStateMachine.getStateName(fromState),
                RefundStateMachine.getStateName(toState), allowed);
        if (!allowed) {
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
     * 记录（但不拦截）管理型/系统型售后状态迁移。
     *
     * <p>用于历史遗留的"管理员直接完结/无需处理"等场景：这些迁移可能不符合
     * 严格状态机（如 0→4），但属于既有业务行为，不应阻断；不过仍需上报到
     * Trace 以便监视，标记为 ADMIN_TRANSITION。</p>
     */
    public void recordAdministrativeRefundTransition(Integer fromState, Integer toState, String refundId) {
        try {
            eventPublisher.publishEvent(new StateTransitionEvent(
                    this, "REFUND", refundId,
                    fromState, toState,
                    RefundStateMachine.getStateName(fromState),
                    RefundStateMachine.getStateName(toState),
                    true, "ADMIN_TRANSITION"));
        } catch (Exception e) {
            log.warn("发布管理型状态迁移事件失败: {}", e.getMessage());
        }
        log.info("管理型售后状态迁移（记录不拦截）: refundId={}, {}→{}", refundId,
                RefundStateMachine.getStateName(fromState), RefundStateMachine.getStateName(toState));
    }

    /**
     * 发布状态迁移事件（上报到监视层）。
     */
    private void publish(String entityType, String entityId,
                          Integer fromState, Integer toState,
                          String fromName, String toName, boolean allowed) {
        try {
            eventPublisher.publishEvent(new StateTransitionEvent(
                    this, entityType, entityId,
                    fromState, toState, fromName, toName, allowed,
                    allowed ? "TRANSITION" : "BLOCKED"));
        } catch (Exception e) {
            log.warn("发布状态迁移事件失败: {}", e.getMessage());
        }
    }
}
