package cn.qihangerp.service.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 售后状态机单元测试
 */
class RefundStateMachineTest {

    private RefundStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new RefundStateMachine();
    }

    @Test
    void testLegalTransitions() {
        // 测试合法状态迁移
        assertTrue(stateMachine.canTransition(0, 2), "售后申请 -> 售后处理中 应该合法");
        assertTrue(stateMachine.canTransition(2, 3), "售后处理中 -> 退款中 应该合法");
        assertTrue(stateMachine.canTransition(3, 4), "退款中 -> 售后成功 应该合法");
        assertTrue(stateMachine.canTransition(0, 5), "售后申请 -> 待用户处理 应该合法");
        assertTrue(stateMachine.canTransition(5, 6), "待用户处理 -> 待买家发货 应该合法");
    }

    @Test
    void testIllegalTransitions() {
        // 测试非法状态跳转
        assertFalse(stateMachine.canTransition(0, 4), "售后申请 -> 售后成功 应该不合法");
        assertFalse(stateMachine.canTransition(0, 3), "售后申请 -> 退款中 应该不合法（需要先处理）");
        assertFalse(stateMachine.canTransition(4, 2), "售后成功 -> 售后处理中 应该不合法");
        assertFalse(stateMachine.canTransition(1, 2), "售后关闭 -> 售后处理中 应该不合法");
    }

    @Test
    void testApprovalRequired() {
        // 测试需要审批的操作
        assertTrue(stateMachine.requiresApproval(0, 1), "售后申请 -> 拒绝售后 需要审批");
        assertTrue(stateMachine.requiresApproval(2, 3), "售后处理中 -> 同意退款 需要审批");
        assertTrue(stateMachine.requiresApproval(2, 1), "售后处理中 -> 拒绝退款 需要审批");
        assertFalse(stateMachine.requiresApproval(0, 2), "售后申请 -> 接受售后 不需要审批");
        assertFalse(stateMachine.requiresApproval(3, 4), "退款中 -> 售后成功 不需要审批");
    }

    @Test
    void testGetAvailableTransitions() {
        // 测试获取可迁移状态列表
        List<RefundState> availableStates = stateMachine.getAvailableTransitions(2); // 售后处理中
        assertTrue(availableStates.contains(RefundState.REFUNDING), "售后处理中应该可以迁移到退款中");
        assertTrue(availableStates.contains(RefundState.CLOSED), "售后处理中应该可以迁移到售后关闭");
        assertTrue(availableStates.contains(RefundState.PLATFORM_PROCESSING), "售后处理中应该可以迁移到平台处理中");
        assertTrue(availableStates.contains(RefundState.PENDING_BUYER_SHIP), "售后处理中应该可以迁移到待买家发货");
        assertFalse(availableStates.contains(RefundState.SUCCESS), "售后处理中不应该直接迁移到售后成功");
    }

    @Test
    void testPlatformProcessing() {
        // 测试平台处理流程
        assertTrue(stateMachine.canTransition(2, 8), "售后处理中 -> 平台处理中 应该合法");
        assertTrue(stateMachine.canTransition(8, 3), "平台处理中 -> 退款中 应该合法");
        assertTrue(stateMachine.canTransition(8, 4), "平台处理中 -> 售后成功 应该合法");
        assertTrue(stateMachine.canTransition(8, 1), "平台处理中 -> 售后关闭 应该合法");
    }

    @Test
    void testGetTransition() {
        // 测试获取迁移规则详情
        StateTransition<RefundState> transition = stateMachine.getTransition(2, 3);
        assertNotNull(transition, "应该能找到售后处理中->退款中的迁移规则");
        assertEquals("同意退款", transition.getAction(), "动作描述应该是'同意退款'");
        assertTrue(transition.isRequiresApproval(), "同意退款需要审批");

        StateTransition<RefundState> rejectTransition = stateMachine.getTransition(0, 1);
        assertNotNull(rejectTransition, "应该能找到售后申请->拒绝售后的迁移规则");
        assertEquals("拒绝售后", rejectTransition.getAction(), "动作描述应该是'拒绝售后'");
        assertTrue(rejectTransition.isRequiresApproval(), "拒绝售后需要审批");
    }

    @Test
    void testInvalidStates() {
        // 测试无效状态码
        assertFalse(stateMachine.canTransition(null, 2), "null状态应该返回false");
        assertFalse(stateMachine.canTransition(0, null), "目标状态为null应该返回false");
        assertFalse(stateMachine.canTransition(999, 2), "不存在的状态码应该返回false");
        assertFalse(stateMachine.canTransition(0, 888), "不存在的目标状态应该返回false");
    }

    @Test
    void testTransitionValidator() {
        // 测试校验器
        assertDoesNotThrow(() ->
                        TransitionValidator.validateRefund(stateMachine, "REFUND001", 0, 2),
                "合法状态迁移不应抛异常"
        );

        TransitionValidator.IllegalStateTransitionException exception = assertThrows(
                TransitionValidator.IllegalStateTransitionException.class,
                () -> TransitionValidator.validateRefund(stateMachine, "REFUND002", 0, 4),
                "非法状态迁移应该抛出异常"
        );
        assertTrue(exception.getMessage().contains("REFUND002"), "异常信息应包含售后单号");
        assertTrue(exception.getMessage().contains("售后申请"), "异常信息应包含当前状态");
        assertTrue(exception.getMessage().contains("售后成功"), "异常信息应包含目标状态");
    }

    @Test
    void testBuyerReturnFlow() {
        // 测试买家退货流程
        assertTrue(stateMachine.canTransition(2, 6), "售后处理中 -> 待买家发货 应该合法");
        assertTrue(stateMachine.canTransition(6, 2), "待买家发货 -> 售后处理中 应该合法");
        assertTrue(stateMachine.canTransition(6, 1), "待买家发货 -> 售后关闭 应该合法");
    }
}
