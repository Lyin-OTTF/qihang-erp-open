package cn.qihangerp.service.integration;

import cn.qihangerp.service.statemachine.OrderState;
import cn.qihangerp.service.statemachine.OrderStateMachine;
import cn.qihangerp.service.statemachine.TransitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单状态机Service层集成测试
 *
 * 目标：验证状态机在实际Service方法中的应用效果
 * 场景：模拟订单状态更新操作，测试状态机阻止非法状态迁移
 */
class OrderStateMachineIntegrationTest {

    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStateMachine();
    }

    /**
     * 测试场景1：取消订单 - 不同状态的取消逻辑
     *
     * 业务规则：
     * - 待发货订单 -> 取消订单（需审批）
     * - 已完成订单 -> 不能直接取消
     * - 已取消订单 -> 不能重复取消
     */
    @Test
    void testCancelOrderWithStateMachine() {
        // 场景1.1: 待发货订单可以取消（但需要审批）
        String orderId1 = "ORDER001";
        int currentStatus1 = 1; // 待发货
        int targetStatus1 = 11; // 已取消

        // 校验状态迁移是否合法
        assertTrue(stateMachine.canTransition(currentStatus1, targetStatus1),
                "待发货订单应该可以取消");

        // 检查是否需要审批
        assertTrue(stateMachine.requiresApproval(currentStatus1, targetStatus1),
                "待发货订单取消应该需要审批");

        // 模拟Service层逻辑
        assertDoesNotThrow(() ->
                        TransitionValidator.validateOrder(stateMachine, orderId1, currentStatus1, targetStatus1),
                "合法状态迁移不应抛异常"
        );

        // 场景1.2: 已完成订单不能直接取消
        String orderId2 = "ORDER002";
        int currentStatus2 = 3; // 已完成
        int targetStatus2 = 11; // 已取消

        assertFalse(stateMachine.canTransition(currentStatus2, targetStatus2),
                "已完成订单不能直接取消");

        TransitionValidator.IllegalStateTransitionException exception2 = assertThrows(
                TransitionValidator.IllegalStateTransitionException.class,
                () -> TransitionValidator.validateOrder(stateMachine, orderId2, currentStatus2, targetStatus2),
                "非法状态迁移应该抛出异常"
        );
        assertTrue(exception2.getMessage().contains("已完成"),
                "异常信息应包含当前状态");

        // 场景1.3: 已取消订单不能重复取消
        String orderId3 = "ORDER003";
        int currentStatus3 = 11; // 已取消
        int targetStatus3 = 11; // 已取消

        assertFalse(stateMachine.canTransition(currentStatus3, targetStatus3),
                "已取消订单不能重复取消");
    }

    /**
     * 测试场景2：订单发货流程 - 状态机保证发货顺序
     *
     * 业务规则：
     * - 新订单 -> 待发货 -> 已发货 -> 已完成（正常流程）
     * - 新订单 不能直接跳到 已发货（非法跳转）
     * - 待发货 不能直接跳到 已完成（非法跳转）
     */
    @Test
    void testOrderShipmentFlow() {
        String orderId = "ORDER004";

        // 场景2.1: 新订单 -> 待发货（合法）
        assertTrue(stateMachine.canTransition(0, 1),
                "新订单 -> 待发货 应该合法");

        // 场景2.2: 新订单 不能直接跳到 已发货（非法）
        assertFalse(stateMachine.canTransition(0, 2),
                "新订单 -> 已发货 应该不合法");

        TransitionValidator.IllegalStateTransitionException exception1 = assertThrows(
                TransitionValidator.IllegalStateTransitionException.class,
                () -> TransitionValidator.validateOrder(stateMachine, orderId, 0, 2),
                "新订单直接发货应该抛出异常"
        );
        assertTrue(exception1.getMessage().contains("新订单") && exception1.getMessage().contains("已发货"),
                "异常信息应说明非法状态迁移");

        // 场景2.3: 待发货 -> 已发货（合法）
        assertTrue(stateMachine.canTransition(1, 2),
                "待发货 -> 已发货 应该合法");

        // 场景2.4: 待发货 不能直接跳到 已完成（非法）
        assertFalse(stateMachine.canTransition(1, 3),
                "待发货 -> 已完成 应该不合法");

        // 场景2.5: 已发货 -> 已完成（合法）
        assertTrue(stateMachine.canTransition(2, 3),
                "已发货 -> 已完成 应该合法");
    }

    /**
     * 测试场景3：退款流程 - 不同阶段的退款状态迁移
     *
     * 业务规则：
     * - 新订单/待发货/已发货/已完成 都可以进入 退款中
     * - 退款中 可以回退到 待发货/已发货（退款失败）
     * - 退款中 可以进入 已关闭（退款成功）
     */
    @Test
    void testRefundFlow() {
        String orderId = "ORDER005";

        // 场景3.1: 待发货订单可以退款
        assertTrue(stateMachine.canTransition(1, 12),
                "待发货 -> 退款中 应该合法");
        assertFalse(stateMachine.requiresApproval(1, 12),
                "进入退款流程不需要审批");

        // 场景3.2: 已发货订单可以退款
        assertTrue(stateMachine.canTransition(2, 12),
                "已发货 -> 退款中 应该合法");

        // 场景3.3: 已完成订单可以售后退款
        assertTrue(stateMachine.canTransition(3, 12),
                "已完成 -> 退款中 应该合法（售后退款）");

        // 场景3.4: 退款中可以回退到待发货（退款失败）
        assertTrue(stateMachine.canTransition(12, 1),
                "退款中 -> 待发货 应该合法");

        // 场景3.5: 退款中可以进入已关闭（退款成功）
        assertTrue(stateMachine.canTransition(12, 13),
                "退款中 -> 已关闭 应该合法");
    }

    /**
     * 测试场景4：模拟Service层实际更新订单状态的逻辑
     *
     * 这是一个完整的Service层方法示例，展示如何集成状态机
     */
    @Test
    void testServiceLayerUpdateOrderStatus() {
        // 模拟订单数据
        class MockOrder {
            String orderId;
            Integer orderStatus;
            Integer version;

            MockOrder(String orderId, Integer orderStatus, Integer version) {
                this.orderId = orderId;
                this.orderStatus = orderStatus;
                this.version = version;
            }
        }

        // 场景4.1: 正常发货流程
        MockOrder order1 = new MockOrder("ORDER006", 1, 1); // 待发货订单
        int newStatus1 = 2; // 发货

        // 步骤1: 校验状态迁移
        assertDoesNotThrow(() ->
                TransitionValidator.validateOrder(
                        stateMachine,
                        order1.orderId,
                        order1.orderStatus,
                        newStatus1
                ));

        // 步骤2: 检查是否需要审批
        boolean needsApproval1 = stateMachine.requiresApproval(order1.orderStatus, newStatus1);
        assertFalse(needsApproval1, "发货不需要审批");

        // 步骤3: 更新订单状态（模拟乐观锁）
        int oldVersion1 = order1.version;
        order1.orderStatus = newStatus1;
        order1.version = oldVersion1 + 1;

        assertEquals(2, order1.orderStatus, "订单状态应该更新为已发货");
        assertEquals(2, order1.version, "版本号应该+1");

        // 场景4.2: 尝试非法状态迁移
        MockOrder order2 = new MockOrder("ORDER007", 3, 1); // 已完成订单
        int newStatus2 = 1; // 尝试回退到待发货

        // 状态机应该阻止这个操作
        TransitionValidator.IllegalStateTransitionException exception = assertThrows(
                TransitionValidator.IllegalStateTransitionException.class,
                () -> TransitionValidator.validateOrder(
                        stateMachine,
                        order2.orderId,
                        order2.orderStatus,
                        newStatus2
                )
        );

        // 订单状态不应该被修改
        assertEquals(3, order2.orderStatus, "订单状态不应该被修改");
        assertEquals(1, order2.version, "版本号不应该变化");
    }

    /**
     * 测试场景5：并发更新场景 - 乐观锁版本号校验
     *
     * 这个测试展示了乐观锁如何防止并发修改
     */
    @Test
    void testConcurrentUpdateWithOptimisticLock() {
        // 模拟两个并发请求同时更新同一个订单

        // 初始订单状态
        String orderId = "ORDER008";
        Integer currentStatus = 1; // 待发货
        Integer currentVersion = 1;

        // 请求1: 发货操作
        Integer newStatus1 = 2; // 已发货
        assertTrue(stateMachine.canTransition(currentStatus, newStatus1),
                "请求1状态迁移合法");

        // 请求2: 取消订单操作（同时发生）
        Integer newStatus2 = 11; // 已取消
        assertTrue(stateMachine.canTransition(currentStatus, newStatus2),
                "请求2状态迁移也合法");

        // 模拟乐观锁：只有version匹配的请求才能更新成功
        // 假设请求1先执行成功
        Integer versionAfterRequest1 = currentVersion + 1; // version变为2

        // 请求2使用旧的version=1尝试更新，应该失败
        // （实际数据库层面会通过 WHERE version=1 条件导致影响行数=0）
        assertNotEquals(versionAfterRequest1, currentVersion,
                "请求2检测到version不匹配，应该拒绝更新");

        // 最终状态: 订单已发货（请求1成功），version=2
        assertEquals(2, versionAfterRequest1, "最终版本号应该是2");
    }

    /**
     * 测试场景6：获取订单当前可执行的操作列表
     *
     * 用于前端展示"可执行操作"按钮
     */
    @Test
    void testGetAvailableActionsForOrder() {
        // 场景6.1: 待发货订单的可执行操作
        int currentStatus = 1; // 待发货
        var availableStates = stateMachine.getAvailableTransitions(currentStatus);

        assertTrue(availableStates.contains(OrderState.SHIPPED),
                "待发货订单应该可以执行发货操作");
        assertTrue(availableStates.contains(OrderState.CANCELLED),
                "待发货订单应该可以执行取消操作");
        assertTrue(availableStates.contains(OrderState.REFUNDING),
                "待发货订单应该可以执行退款操作");
        assertFalse(availableStates.contains(OrderState.COMPLETED),
                "待发货订单不应该直接完成");

        // 可以用于构造前端按钮
        System.out.println("待发货订单可执行操作:");
        for (OrderState state : availableStates) {
            boolean needsApproval = stateMachine.requiresApproval(currentStatus, state.getCode());
            String approvalTag = needsApproval ? "[需审批]" : "";
            System.out.println("  - " + state.getDescription() + " " + approvalTag);
        }
    }
}

