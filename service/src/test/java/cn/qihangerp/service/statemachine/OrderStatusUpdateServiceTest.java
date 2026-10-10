package cn.qihangerp.service.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderStatusUpdateService 集成测试
 *
 * 这个测试展示了如何在实际Service方法中使用状态机
 */
class OrderStatusUpdateServiceTest {

    private OrderStatusUpdateService service;

    @BeforeEach
    void setUp() {
        service = new OrderStatusUpdateService();
    }

    /**
     * 测试正常的状态更新流程
     */
    @Test
    void testUpdateOrderStatusSuccess() {
        String orderId = "TEST_ORDER_001";
        Long orderDatabaseId = 100L;
        Integer currentStatus = 1;  // 待发货
        Integer targetStatus = 2;   // 已发货
        Integer currentVersion = 1;
        String operator = "张三";

        // 执行更新
        OrderStatusUpdateService.UpdateResult result = service.updateOrderStatus(
                orderId,
                orderDatabaseId,
                currentStatus,
                targetStatus,
                currentVersion,
                operator
        );

        // 验证结果
        assertTrue(result.isSuccess(), "正常状态迁移应该成功");
        assertEquals(OrderStatusUpdateService.UpdateResult.UpdateStatus.SUCCESS,
                result.getStatus(),
                "状态应该是SUCCESS");
        assertNotNull(result.getMessage(), "应该有成功消息");
    }

    /**
     * 测试非法状态迁移被拦截
     */
    @Test
    void testUpdateOrderStatusIllegalTransition() {
        String orderId = "TEST_ORDER_002";
        Long orderDatabaseId = 101L;
        Integer currentStatus = 0;  // 新订单
        Integer targetStatus = 3;   // 已完成（非法跳转）
        Integer currentVersion = 1;
        String operator = "李四";

        // 执行更新
        OrderStatusUpdateService.UpdateResult result = service.updateOrderStatus(
                orderId,
                orderDatabaseId,
                currentStatus,
                targetStatus,
                currentVersion,
                operator
        );

        // 验证结果
        assertFalse(result.isSuccess(), "非法状态迁移应该失败");
        assertEquals(OrderStatusUpdateService.UpdateResult.UpdateStatus.FAILURE,
                result.getStatus(),
                "状态应该是FAILURE");
        assertTrue(result.getMessage().contains("不合法"),
                "错误信息应该说明状态迁移不合法");
    }

    /**
     * 测试需要审批的操作
     */
    @Test
    void testUpdateOrderStatusNeedApproval() {
        String orderId = "TEST_ORDER_003";
        Long orderDatabaseId = 102L;
        Integer currentStatus = 1;  // 待发货
        Integer targetStatus = 11;  // 已取消（需要审批）
        Integer currentVersion = 1;
        String operator = "王五";

        // 执行更新
        OrderStatusUpdateService.UpdateResult result = service.updateOrderStatus(
                orderId,
                orderDatabaseId,
                currentStatus,
                targetStatus,
                currentVersion,
                operator
        );

        // 验证结果
        assertFalse(result.isSuccess(), "需要审批的操作不应该直接成功");
        assertEquals(OrderStatusUpdateService.UpdateResult.UpdateStatus.NEED_APPROVAL,
                result.getStatus(),
                "状态应该是NEED_APPROVAL");
        assertTrue(result.getMessage().contains("审批"),
                "消息应该提示需要审批");
    }

    /**
     * 测试获取可执行操作列表
     */
    @Test
    void testGetAvailableActions() {
        // 测试待发货订单的可执行操作
        Integer currentStatus = 1; // 待发货
        List<OrderState> availableActions = service.getAvailableActions(currentStatus);

        assertNotNull(availableActions, "可执行操作列表不应为null");
        assertFalse(availableActions.isEmpty(), "待发货订单应该有可执行操作");

        // 验证包含预期的操作
        assertTrue(availableActions.contains(OrderState.SHIPPED),
                "应该包含发货操作");
        assertTrue(availableActions.contains(OrderState.CANCELLED),
                "应该包含取消操作");
        assertTrue(availableActions.contains(OrderState.REFUNDING),
                "应该包含退款操作");

        // 验证不包含非法操作
        assertFalse(availableActions.contains(OrderState.COMPLETED),
                "不应该包含直接完成操作");

        // 打印可执行操作（便于调试）
        System.out.println("待发货订单可执行操作:");
        for (OrderState action : availableActions) {
            boolean needsApproval = service.requiresApproval(currentStatus, action.getCode());
            System.out.println(String.format("  - %s (code=%d) %s",
                    action.getDescription(),
                    action.getCode(),
                    needsApproval ? "[需审批]" : ""
            ));
        }
    }

    /**
     * 测试审批检查功能
     */
    @Test
    void testRequiresApproval() {
        // 发货操作不需要审批
        assertFalse(service.requiresApproval(1, 2),
                "待发货->已发货 不需要审批");

        // 取消操作需要审批
        assertTrue(service.requiresApproval(1, 11),
                "待发货->已取消 需要审批");

        // 锁定订单取消需要审批
        assertTrue(service.requiresApproval(22, 11),
                "锁定->已取消 需要审批");

        // 进入退款流程不需要审批
        assertFalse(service.requiresApproval(1, 12),
                "待发货->退款中 不需要审批");
    }

    /**
     * 测试完整的订单生命周期
     */
    @Test
    void testOrderLifecycle() {
        String orderId = "TEST_ORDER_004";
        Long orderDatabaseId = 103L;
        String operator = "赵六";

        // 阶段1: 新订单 -> 待发货
        OrderStatusUpdateService.UpdateResult result1 = service.updateOrderStatus(
                orderId, orderDatabaseId, 0, 1, 1, operator
        );
        assertTrue(result1.isSuccess(), "新订单->待发货应该成功");

        // 阶段2: 待发货 -> 已发货
        OrderStatusUpdateService.UpdateResult result2 = service.updateOrderStatus(
                orderId, orderDatabaseId, 1, 2, 2, operator
        );
        assertTrue(result2.isSuccess(), "待发货->已发货应该成功");

        // 阶段3: 已发货 -> 已完成
        OrderStatusUpdateService.UpdateResult result3 = service.updateOrderStatus(
                orderId, orderDatabaseId, 2, 3, 3, operator
        );
        assertTrue(result3.isSuccess(), "已发货->已完成应该成功");

        // 阶段4: 尝试回退（应该失败）
        OrderStatusUpdateService.UpdateResult result4 = service.updateOrderStatus(
                orderId, orderDatabaseId, 3, 1, 4, operator
        );
        assertFalse(result4.isSuccess(), "已完成不应该回退到待发货");
    }

    /**
     * 测试退款流程
     */
    @Test
    void testRefundFlow() {
        String orderId = "TEST_ORDER_005";
        Long orderDatabaseId = 104L;
        String operator = "孙七";

        // 场景1: 待发货订单退款
        OrderStatusUpdateService.UpdateResult result1 = service.updateOrderStatus(
                orderId, orderDatabaseId, 1, 12, 1, operator
        );
        assertTrue(result1.isSuccess(), "待发货->退款中应该成功");

        // 场景2: 退款失败，回退到待发货
        OrderStatusUpdateService.UpdateResult result2 = service.updateOrderStatus(
                orderId, orderDatabaseId, 12, 1, 2, operator
        );
        assertTrue(result2.isSuccess(), "退款中->待发货应该成功");

        // 场景3: 已完成订单售后退款
        OrderStatusUpdateService.UpdateResult result3 = service.updateOrderStatus(
                orderId, orderDatabaseId, 3, 12, 3, operator
        );
        assertTrue(result3.isSuccess(), "已完成->退款中应该成功（售后退款）");

        // 场景4: 退款成功，关闭订单
        OrderStatusUpdateService.UpdateResult result4 = service.updateOrderStatus(
                orderId, orderDatabaseId, 12, 13, 4, operator
        );
        assertTrue(result4.isSuccess(), "退款中->已关闭应该成功");
    }
}
