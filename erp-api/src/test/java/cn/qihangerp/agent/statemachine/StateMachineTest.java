package cn.qihangerp.agent.statemachine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机单元测试（搁置项攻关-任务1）。
 *
 * <p>验证订单状态机和售后状态机的合法迁移、非法迁移拦截、终态判断、
 * 状态名映射等核心逻辑。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
class StateMachineTest {

    // ==================== 订单状态机 ====================

    @Test
    void order_validTransitions_areAllowed() {
        // 新订单 → 待付款/待发货/已取消
        assertTrue(OrderStateMachine.canTransition(0, 21));
        assertTrue(OrderStateMachine.canTransition(0, 1));
        assertTrue(OrderStateMachine.canTransition(0, 11));

        // 待付款 → 待发货/已取消/已关闭
        assertTrue(OrderStateMachine.canTransition(21, 1));
        assertTrue(OrderStateMachine.canTransition(21, 11));
        assertTrue(OrderStateMachine.canTransition(21, 13));

        // 待发货 → 已发货/部分发货/已取消/退款中
        assertTrue(OrderStateMachine.canTransition(1, 2));
        assertTrue(OrderStateMachine.canTransition(1, 101));
        assertTrue(OrderStateMachine.canTransition(1, 11));
        assertTrue(OrderStateMachine.canTransition(1, 12));

        // 部分发货 → 已发货/退款中
        assertTrue(OrderStateMachine.canTransition(101, 2));
        assertTrue(OrderStateMachine.canTransition(101, 12));

        // 已发货 → 已完成/退款中
        assertTrue(OrderStateMachine.canTransition(2, 3));
        assertTrue(OrderStateMachine.canTransition(2, 12));

        // 退款中 → 已完成/已关闭/已取消
        assertTrue(OrderStateMachine.canTransition(12, 3));
        assertTrue(OrderStateMachine.canTransition(12, 13));
        assertTrue(OrderStateMachine.canTransition(12, 11));

        // 锁定 → 待发货/已取消
        assertTrue(OrderStateMachine.canTransition(22, 1));
        assertTrue(OrderStateMachine.canTransition(22, 11));
    }

    @Test
    void order_invalidTransitions_areRejected() {
        // 待发货不能直接到已完成（跳过已发货）
        assertFalse(OrderStateMachine.canTransition(1, 3),
                "待发货不能直接到已完成，必须经过已发货");

        // 新订单不能直接到已完成
        assertFalse(OrderStateMachine.canTransition(0, 3));

        // 已完成是终态，不能再迁移
        assertFalse(OrderStateMachine.canTransition(3, 1));
        assertFalse(OrderStateMachine.canTransition(3, 2));
        assertFalse(OrderStateMachine.canTransition(3, 11));

        // 已取消是终态
        assertFalse(OrderStateMachine.canTransition(11, 1));

        // 已关闭是终态
        assertFalse(OrderStateMachine.canTransition(13, 1));

        // 删除是终态
        assertFalse(OrderStateMachine.canTransition(29, 1));

        // 待付款不能直接到已发货
        assertFalse(OrderStateMachine.canTransition(21, 2),
                "待付款不能直接到已发货，必须先到待发货");
    }

    @Test
    void order_sameState_isAllowed() {
        assertTrue(OrderStateMachine.canTransition(1, 1));
        assertTrue(OrderStateMachine.canTransition(0, 0));
        assertTrue(OrderStateMachine.canTransition(3, 3));
    }

    @Test
    void order_nullStates_areRejected() {
        assertFalse(OrderStateMachine.canTransition(null, 1));
        assertFalse(OrderStateMachine.canTransition(1, null));
        assertFalse(OrderStateMachine.canTransition(null, null));
    }

    @Test
    void order_terminalStates_areCorrect() {
        assertTrue(OrderStateMachine.isTerminal(3), "已完成是终态");
        assertTrue(OrderStateMachine.isTerminal(11), "已取消是终态");
        assertTrue(OrderStateMachine.isTerminal(13), "已关闭是终态");
        assertTrue(OrderStateMachine.isTerminal(29), "删除是终态");

        assertFalse(OrderStateMachine.isTerminal(0), "新订单不是终态");
        assertFalse(OrderStateMachine.isTerminal(1), "待发货不是终态");
        assertFalse(OrderStateMachine.isTerminal(2), "已发货不是终态");
        assertFalse(OrderStateMachine.isTerminal(21), "待付款不是终态");
        assertFalse(OrderStateMachine.isTerminal(12), "退款中不是终态");
    }

    @Test
    void order_stateNames_areCorrect() {
        assertEquals("新订单", OrderStateMachine.getStateName(0));
        assertEquals("待发货", OrderStateMachine.getStateName(1));
        assertEquals("已发货", OrderStateMachine.getStateName(2));
        assertEquals("已完成", OrderStateMachine.getStateName(3));
        assertEquals("已取消", OrderStateMachine.getStateName(11));
        assertEquals("退款中", OrderStateMachine.getStateName(12));
        assertEquals("已关闭", OrderStateMachine.getStateName(13));
        assertEquals("待付款", OrderStateMachine.getStateName(21));
        assertEquals("锁定", OrderStateMachine.getStateName(22));
        assertEquals("删除", OrderStateMachine.getStateName(29));
        assertEquals("部分发货", OrderStateMachine.getStateName(101));
        assertEquals("未知", OrderStateMachine.getStateName(null));
        assertTrue(OrderStateMachine.getStateName(999).startsWith("未知"));
    }

    @Test
    void order_allowedTransitions_areReturned() {
        Set<Integer> fromPending = OrderStateMachine.getAllowedTransitions(1);
        assertTrue(fromPending.contains(2));
        assertTrue(fromPending.contains(101));
        assertTrue(fromPending.contains(11));
        assertTrue(fromPending.contains(12));
        assertEquals(4, fromPending.size());

        // 终态没有允许的迁移
        Set<Integer> fromCompleted = OrderStateMachine.getAllowedTransitions(3);
        assertTrue(fromCompleted.isEmpty());
    }

    // ==================== 售后状态机 ====================

    @Test
    void refund_validTransitions_areAllowed() {
        // 售后申请 → 售后关闭/售后处理中/平台处理中
        assertTrue(RefundStateMachine.canTransition(0, 1));
        assertTrue(RefundStateMachine.canTransition(0, 2));
        assertTrue(RefundStateMachine.canTransition(0, 8));

        // 售后处理中 → 退款中/待买家发货/待用户处理/售后关闭/平台处理中
        assertTrue(RefundStateMachine.canTransition(2, 3));
        assertTrue(RefundStateMachine.canTransition(2, 6));
        assertTrue(RefundStateMachine.canTransition(2, 5));
        assertTrue(RefundStateMachine.canTransition(2, 1));
        assertTrue(RefundStateMachine.canTransition(2, 8));

        // 退款中 → 售后成功/售后关闭/售后处理中
        assertTrue(RefundStateMachine.canTransition(3, 4));
        assertTrue(RefundStateMachine.canTransition(3, 1));
        assertTrue(RefundStateMachine.canTransition(3, 2));

        // 待用户处理 → 售后处理中/售后关闭
        assertTrue(RefundStateMachine.canTransition(5, 2));
        assertTrue(RefundStateMachine.canTransition(5, 1));

        // 待买家发货 → 售后处理中/售后关闭
        assertTrue(RefundStateMachine.canTransition(6, 2));
        assertTrue(RefundStateMachine.canTransition(6, 1));

        // 平台处理中 → 售后处理中/退款中/售后关闭/售后成功
        assertTrue(RefundStateMachine.canTransition(8, 2));
        assertTrue(RefundStateMachine.canTransition(8, 3));
        assertTrue(RefundStateMachine.canTransition(8, 1));
        assertTrue(RefundStateMachine.canTransition(8, 4));
    }

    @Test
    void refund_invalidTransitions_areRejected() {
        // 售后申请不能直接到售后成功
        assertFalse(RefundStateMachine.canTransition(0, 4),
                "售后申请不能直接到售后成功，必须经过处理中/退款中");

        // 售后成功是终态
        assertFalse(RefundStateMachine.canTransition(4, 0));
        assertFalse(RefundStateMachine.canTransition(4, 2));

        // 售后关闭是终态
        assertFalse(RefundStateMachine.canTransition(1, 0));
        assertFalse(RefundStateMachine.canTransition(1, 2));

        // 待买家发货不能直接到平台处理中
        assertFalse(RefundStateMachine.canTransition(6, 8),
                "待买家发货不能直接到平台处理中");
    }

    @Test
    void refund_terminalStates_areCorrect() {
        assertTrue(RefundStateMachine.isTerminal(1), "售后关闭是终态");
        assertTrue(RefundStateMachine.isTerminal(4), "售后成功是终态");
        assertFalse(RefundStateMachine.isTerminal(0), "售后申请不是终态");
        assertFalse(RefundStateMachine.isTerminal(2), "售后处理中不是终态");
        assertFalse(RefundStateMachine.isTerminal(3), "退款中不是终态");
    }

    // ==================== TransitionValidator ====================

    @Test
    void validator_validOrderTransition_passes() {
        TransitionValidator validator = new TransitionValidator();
        assertDoesNotThrow(() -> validator.validateOrderTransition(1, 2, "ORD001"));
        assertDoesNotThrow(() -> validator.validateOrderTransition(0, 21, "ORD002"));
    }

    @Test
    void validator_invalidOrderTransition_throws() {
        TransitionValidator validator = new TransitionValidator();
        TransitionValidator.IllegalTransitionException ex = assertThrows(
                TransitionValidator.IllegalTransitionException.class,
                () -> validator.validateOrderTransition(1, 3, "ORD003"));
        assertTrue(ex.getMessage().contains("ORD003"));
        assertTrue(ex.getMessage().contains("待发货"));
        assertTrue(ex.getMessage().contains("已完成"));
    }

    @Test
    void validator_validRefundTransition_passes() {
        TransitionValidator validator = new TransitionValidator();
        assertDoesNotThrow(() -> validator.validateRefundTransition(0, 2, "REF001"));
        assertDoesNotThrow(() -> validator.validateRefundTransition(2, 3, "REF002"));
    }

    @Test
    void validator_invalidRefundTransition_throws() {
        TransitionValidator validator = new TransitionValidator();
        assertThrows(TransitionValidator.IllegalTransitionException.class,
                () -> validator.validateRefundTransition(0, 4, "REF003"));
    }
}
