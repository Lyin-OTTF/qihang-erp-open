package cn.qihangerp.agent.policy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 策略注册表单元测试（搁置项攻关-任务6补全）。
 *
 * <p>验证：@Tool方法扫描、策略元数据提取、写操作工具筛选、
 * 需要审批工具筛选、按工具名查询。使用动态代理创建ApplicationContext stub。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
class PolicyRegistryTest {

    /** 测试Bean：包含不同自治等级的@Tool方法 */
    static class TestToolBean {
        @Tool(description = "查询订单")
        @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "订单查询")
        public String getOrder(String orderId) { return "order"; }

        @Tool(description = "更新备注")
        @ToolPolicy(level = AutonomyLevel.EXECUTE, category = "订单操作")
        public void updateMemo(String orderId, String memo) { }

        @Tool(description = "取消订单")
        @ToolPolicy(level = AutonomyLevel.ESCALATE, category = "订单操作")
        public void cancelOrder(String orderId) { }

        @Tool(description = "无策略注解的工具")
        public String noPolicyTool() { return "test"; }

        public void notATool() { }
    }

    /** 用动态代理创建ApplicationContext stub */
    private ApplicationContext createStubContext(Map<String, Object> beans) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBeanDefinitionNames":
                    return beans.keySet().toArray(new String[0]);
                case "getBean":
                    if (args != null && args.length > 0 && args[0] instanceof String name) {
                        return beans.get(name);
                    }
                    return null;
                default:
                    // 返回合理默认值
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType.isArray()) return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                    if (returnType == String.class) return "";
                    return null;
            }
        };
        return (ApplicationContext) Proxy.newProxyInstance(
                ApplicationContext.class.getClassLoader(),
                new Class<?>[]{ApplicationContext.class},
                handler);
    }

    private PolicyRegistry createRegistryWithTestBean() {
        ApplicationContext ctx = createStubContext(Map.of("testToolBean", new TestToolBean()));
        PolicyRegistry registry = new PolicyRegistry(ctx);
        registry.init();
        return registry;
    }

    @Test
    void init_scansToolMethods() {
        PolicyRegistry registry = createRegistryWithTestBean();
        Collection<PolicyRegistry.ToolPolicyMetadata> all = registry.getAllPolicies();
        assertEquals(4, all.size(), "应注册4个@Tool方法（含无@ToolPolicy的默认策略）");
    }

    @Test
    void getPolicy_returnsCorrectMetadata() {
        PolicyRegistry registry = createRegistryWithTestBean();

        PolicyRegistry.ToolPolicyMetadata observe = registry.getPolicy("TestToolBean.getOrder");
        assertNotNull(observe);
        assertEquals(AutonomyLevel.OBSERVE, observe.getAutonomyLevel());
        assertEquals("订单查询", observe.getCategory());
        assertFalse(observe.isWrite());
        assertFalse(observe.isRequiresApproval());

        PolicyRegistry.ToolPolicyMetadata execute = registry.getPolicy("TestToolBean.updateMemo");
        assertNotNull(execute);
        assertEquals(AutonomyLevel.EXECUTE, execute.getAutonomyLevel());
        assertTrue(execute.isWrite());
        assertFalse(execute.isRequiresApproval());

        PolicyRegistry.ToolPolicyMetadata escalate = registry.getPolicy("TestToolBean.cancelOrder");
        assertNotNull(escalate);
        assertEquals(AutonomyLevel.ESCALATE, escalate.getAutonomyLevel());
        assertTrue(escalate.isWrite());
        assertTrue(escalate.isRequiresApproval());
    }

    @Test
    void getWriteTools_filtersOnlyWrite() {
        PolicyRegistry registry = createRegistryWithTestBean();
        List<PolicyRegistry.ToolPolicyMetadata> writeTools = registry.getWriteTools();

        long writeCount = writeTools.stream()
                .filter(t -> t.getAutonomyLevel() == AutonomyLevel.EXECUTE
                        || t.getAutonomyLevel() == AutonomyLevel.ESCALATE)
                .count();
        assertEquals(2, writeCount, "应有2个写操作工具");

        boolean hasObserve = writeTools.stream()
                .anyMatch(t -> t.getAutonomyLevel() == AutonomyLevel.OBSERVE);
        assertFalse(hasObserve, "写操作列表不应包含OBSERVE工具");
    }

    @Test
    void getApprovalRequiredTools_filtersOnlyEscalate() {
        PolicyRegistry registry = createRegistryWithTestBean();
        List<PolicyRegistry.ToolPolicyMetadata> approvalTools = registry.getApprovalRequiredTools();

        assertEquals(1, approvalTools.size(), "应有1个需要审批的工具");
        assertEquals("TestToolBean.cancelOrder", approvalTools.get(0).getToolName());
    }

    @Test
    void getPolicy_nonExistent_returnsNull() {
        PolicyRegistry registry = createRegistryWithTestBean();
        assertNull(registry.getPolicy("NonExistent.tool"));
    }

    @Test
    void noPolicyTool_getsDefaultObserve() {
        PolicyRegistry registry = createRegistryWithTestBean();
        PolicyRegistry.ToolPolicyMetadata noPolicy = registry.getPolicy("TestToolBean.noPolicyTool");
        assertNotNull(noPolicy, "无@ToolPolicy的@Tool方法应使用默认策略注册");
        assertEquals(AutonomyLevel.OBSERVE, noPolicy.getAutonomyLevel(), "默认策略应为OBSERVE");
        assertEquals("未分类", noPolicy.getCategory());
    }

    @Test
    void metadata_fieldsAreCorrect() {
        PolicyRegistry registry = createRegistryWithTestBean();
        PolicyRegistry.ToolPolicyMetadata meta = registry.getPolicy("TestToolBean.cancelOrder");

        assertEquals("TestToolBean.cancelOrder", meta.getToolName());
        assertTrue(meta.isRequiresTenantIsolation(), "默认需要租户隔离");
        assertTrue(meta.isAllowConcurrent(), "默认允许并发");
        assertNotNull(meta.getDescription());
    }
}
