package cn.qihangerp.service.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单状态机单元测试
 */
class OrderStateMachineTest {

    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStateMachine();
    }

    @Test
    void testLegalTransitions() {
        // 测试合法状态迁移
        assertTrue(stateMachine.canTransition(21, 0), "待付款 -> 新订单 应该合法");
        assertTrue(stateMachine.canTransition(0, 1), "新订单 -> 待发货 应该合法");
        assertTrue(stateMachine.canTransition(1, 2), "待发货 -> 已发货 应该合法");
        assertTrue(stateMachine.canTransition(2, 3), "已发货 -> 已完成 应该合法");
        assertTrue(stateMachine.canTransition(1, 101), "待发货 -> 部分发货 应该合法");
        assertTrue(stateMachine.canTransition(101, 2), "部分发货 -> 已发货 应该合法");
    }

    @Test
    void testIllegalTransitions() {
        // 测试非法状态跳转（重点：防止直接从待发货跳到已完成）
        assertFalse(stateMachine.canTransition(1, 3), "待发货 -> 已完成 应该不合法");
        assertFalse(stateMachine.canTransition(0, 2), "新订单 -> 已发货 应该不合法");
        assertFalse(stateMachine.canTransition(3, 1), "已完成 -> 待发货 应该不合法");
        assertFalse(stateMachine.canTransition(11, 1), "已取消 -> 待发货 应该不合法");
    }

    @Test
    void testRefundTransitions() {
        // 测试退款相关状态迁移
        assertTrue(stateMachine.canTransition(0, 12), "新订单 -> 退款中 应该合法");
        assertTrue(stateMachine.canTransition(1, 12), "待发货 -> 退款中 应该合法");
        assertTrue(stateMachine.canTransition(2, 12), "已发货 -> 退款中 应该合法");
        assertTrue(stateMachine.canTransition(3, 12), "已完成 -> 退款中 应该合法（售后退款）");
        assertTrue(stateMachine.canTransition(12, 13), "退款中 -> 已关闭 应该合法");
    }

    @Test
    void testApprovalRequired() {
        // 测试需要审批的操作
        assertTrue(stateMachine.requiresApproval(1, 11), "待发货 -> 取消订单 需要审批");
        assertTrue(stateMachine.requiresApproval(22, 11), "锁定 -> 取消订单 需要审批");
        assertFalse(stateMachine.requiresApproval(1, 2), "待发货 -> 已发货 不需要审批");
    }

    @Test
    void testGetAvailableTransitions() {
        // 测试获取可迁移状态列表
        List<OrderState> availableStates = stateMachine.getAvailableTransitions(1); // 待发货
        assertTrue(availableStates.contains(OrderState.SHIPPED), "待发货应该可以迁移到已发货");
        assertTrue(availableStates.contains(OrderState.PARTIAL_SHIPPED), "待发货应该可以迁移到部分发货");
        assertTrue(availableStates.contains(OrderState.CANCELLED), "待发货应该可以迁移到已取消");
        assertTrue(availableStates.contains(OrderState.REFUNDING), "待发货应该可以迁移到退款中");
        assertFalse(availableStates.contains(OrderState.COMPLETED), "待发货不应该直接迁移到已完成");
    }

    @Test
    void testGetTransition() {
        // 测试获取迁移规则详情
        StateTransition<OrderState> transition = stateMachine.getTransition(1, 2);
        assertNotNull(transition, "应该能找到待发货->已发货的迁移规则");
        assertEquals("发货", transition.getAction(), "动作描述应该是'发货'");
        assertFalse(transition.isRequiresApproval(), "普通发货不需要审批");

        StateTransition<OrderState> cancelTransition = stateMachine.getTransition(1, 11);
        assertNotNull(cancelTransition, "应该能找到待发货->取消的迁移规则");
        assertEquals("取消订单", cancelTransition.getAction(), "动作描述应该是'取消订单'");
        assertTrue(cancelTransition.isRequiresApproval(), "取消待发货订单需要审批");
    }

    @Test
    void testInvalidStates() {
        // 测试无效状态码
        assertFalse(stateMachine.canTransition(null, 1), "null状态应该返回false");
        assertFalse(stateMachine.canTransition(1, null), "目标状态为null应该返回false");
        assertFalse(stateMachine.canTransition(999, 1), "不存在的状态码应该返回false");
        assertFalse(stateMachine.canTransition(1, 888), "不存在的目标状态应该返回false");
    }

    @Test
    void testTransitionValidator() {
        // 测试校验器
        assertDoesNotThrow(() ->
                TransitionValidator.validateOrder(stateMachine, "TEST001", 1, 2),
                "合法状态迁移不应抛异常"
        );

        TransitionValidator.IllegalStateTransitionException exception = assertThrows(
                TransitionValidator.IllegalStateTransitionException.class,
                () -> TransitionValidator.validateOrder(stateMachine, "TEST002", 1, 3),
                "非法状态迁移应该抛出异常"
        );
        assertTrue(exception.getMessage().contains("TEST002"), "异常信息应包含订单号");
        assertTrue(exception.getMessage().contains("待发货"), "异常信息应包含当前状态");
        assertTrue(exception.getMessage().contains("已完成"), "异常信息应包含目标状态");
    }
}
