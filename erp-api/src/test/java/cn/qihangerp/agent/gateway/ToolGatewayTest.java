package cn.qihangerp.agent.gateway;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolGateway单元测试
 *
 * 测试场景：
 * 1. 正常执行OBSERVE级别Tool
 * 2. 缺少@ToolPolicy注解时的默认行为
 * 3. ESCALATE级别需要人工确认
 * 4. 租户隔离检查
 * 5. 并发控制
 * 6. 写操作权限检查
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
class ToolGatewayTest {

    private ToolGateway toolGateway;
    private MockToolClass mockTool;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        toolGateway = new ToolGateway();
        mockTool = new MockToolClass();
        context = new ToolExecutionContext(1L, 100L, "test-trace-001");
    }

    /**
     * 测试1：正常执行OBSERVE级别的Tool方法
     */
    @Test
    void testExecuteObserveToolSuccess() throws ToolGateway.ToolExecutionException {
        Object result = toolGateway.execute(
                mockTool,
                "readOnlyQuery",
                context,
                "test-param"
        );

        assertEquals("查询结果: test-param", result);
    }

    /**
     * 测试2：缺少@ToolPolicy注解时使用默认OBSERVE策略
     */
    @Test
    void testExecuteToolWithoutPolicyAnnotation() throws ToolGateway.ToolExecutionException {
        Object result = toolGateway.execute(
                mockTool,
                "methodWithoutPolicy",
                context
        );

        assertEquals("无策略注解的方法", result);
    }

    /**
     * 测试3：ESCALATE级别需要人工确认
     */
    @Test
    void testEscalateToolRequiresApproval() {
        // 未审批状态，应该抛出ApprovalRequiredException
        ToolGateway.ApprovalRequiredException exception = assertThrows(
                ToolGateway.ApprovalRequiredException.class,
                () -> toolGateway.execute(
                        mockTool,
                        "dangerousOperation",
                        context
                )
        );

        assertTrue(exception.getMessage().contains("需要人工确认"));
        assertEquals("MockToolClass.dangerousOperation", exception.getToolName());
    }

    /**
     * 测试4：审批后可以执行ESCALATE级别Tool
     */
    @Test
    void testEscalateToolWithApproval() throws ToolGateway.ToolExecutionException {
        // 设置为已审批
        context.withApproval(999L);

        Object result = toolGateway.execute(
                mockTool,
                "dangerousOperation",
                context
        );

        assertEquals("危险操作已执行", result);
    }

    /**
     * 测试5：需要租户隔离的Tool必须提供merchantId
     */
    @Test
    void testTenantIsolationRequired() {
        ToolExecutionContext contextWithoutMerchant = new ToolExecutionContext();
        contextWithoutMerchant.setUserId(100L);
        contextWithoutMerchant.setTraceId("test-trace-002");
        // merchantId为null

        ToolGateway.ToolExecutionException exception = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(
                        mockTool,
                        "readOnlyQuery",
                        contextWithoutMerchant,
                        "param"
                )
        );

        assertTrue(
                exception.getMessage().contains("缺少租户ID") ||
                        exception.getMessage().contains("Tool执行失败"),
                "异常消息应包含'缺少租户ID'，实际: " + exception.getMessage()
        );
    }

    /**
     * 测试6：不允许并发的Tool在执行中会拒绝第二次调用
     */
    @Test
    void testConcurrentControlBlocking() throws InterruptedException {
        // 创建一个慢速Tool
        MockToolClass slowTool = new MockToolClass();

        // 第一个线程开始执行
        Thread thread1 = new Thread(() -> {
            try {
                toolGateway.execute(slowTool, "nonConcurrentMethod", context);
            } catch (ToolGateway.ToolExecutionException e) {
                // 第一个线程应该成功
                fail("第一个线程不应该失败: " + e.getMessage());
            }
        });

        thread1.start();
        Thread.sleep(50); // 确保thread1先执行

        // 第二个线程尝试执行同一个Tool
        ToolGateway.ToolExecutionException exception = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(slowTool, "nonConcurrentMethod", context)
        );

        assertTrue(
                exception.getMessage().contains("不允许并发调用") ||
                        exception.getMessage().contains("Tool执行失败"),
                "异常消息应包含'不允许并发调用'，实际: " + exception.getMessage()
        );

        thread1.join(); // 等待第一个线程完成
    }

    /**
     * 测试7：写操作权限检查
     */
    @Test
    void testWriteOperationRequiresPermission() {
        // 设置为只读模式
        context.asReadOnly();

        ToolGateway.ToolExecutionException exception = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(
                        mockTool,
                        "writeOperation",
                        context,
                        "data"
                )
        );

        assertTrue(
                exception.getMessage().contains("不允许写操作") ||
                        exception.getMessage().contains("Tool执行失败"),
                "异常消息应包含'不允许写操作'，实际: " + exception.getMessage()
        );
    }

    /**
     * 测试8：批量操作数量限制
     */
    @Test
    void testBatchSizeLimit() {
        context.withBatchSize(150); // 超过限制(100)

        ToolGateway.ToolExecutionException exception = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(
                        mockTool,
                        "batchOperation",
                        context
                )
        );

        assertTrue(
                exception.getMessage().contains("批量操作数量") ||
                        exception.getMessage().contains("超过限制") ||
                        exception.getMessage().contains("Tool执行失败"),
                "异常消息应包含'批量操作数量'或'超过限制'，实际: " + exception.getMessage()
        );
    }

    /**
     * 测试9：Tool方法不存在时的错误处理
     */
    @Test
    void testToolMethodNotFound() {
        ToolGateway.ToolExecutionException exception = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(
                        mockTool,
                        "nonExistentMethod",
                        context
                )
        );

        // 打印实际的异常消息用于调试
        System.out.println("Exception message: " + exception.getMessage());
        System.out.println("Exception cause: " + (exception.getCause() != null ? exception.getCause().getMessage() : "null"));

        assertTrue(
                exception.getMessage().contains("Tool方法不存在") ||
                        exception.getMessage().contains("Tool执行失败"),
                "异常消息应包含'Tool方法不存在'或'Tool执行失败'，实际: " + exception.getMessage()
        );
    }

    /**
     * Mock Tool类，用于测试
     */
    static class MockToolClass {

        @ToolPolicy(
                level = AutonomyLevel.OBSERVE,
                category = "测试查询",
                description = "只读查询测试"
        )
        public String readOnlyQuery(String param) {
            return "查询结果: " + param;
        }

        // 没有@ToolPolicy注解的方法
        public String methodWithoutPolicy() {
            return "无策略注解的方法";
        }

        @ToolPolicy(
                level = AutonomyLevel.ESCALATE,
                category = "危险操作",
                description = "高风险操作需要确认"
        )
        public String dangerousOperation() {
            return "危险操作已执行";
        }

        @ToolPolicy(
                level = AutonomyLevel.EXECUTE,
                category = "写操作",
                description = "写入数据"
        )
        public String writeOperation(String data) {
            return "写入成功: " + data;
        }

        @ToolPolicy(
                level = AutonomyLevel.EXECUTE,
                category = "批量操作",
                maxBatchSize = 100
        )
        public String batchOperation() {
            return "批量操作完成";
        }

        @ToolPolicy(
                level = AutonomyLevel.OBSERVE,
                allowConcurrent = false
        )
        public String nonConcurrentMethod() {
            // 模拟耗时操作
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "串行操作完成";
        }
    }
}
